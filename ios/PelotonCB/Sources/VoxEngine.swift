// VoxEngine.swift
// PROTOCOL §16 voice-activated transmit for iOS — twin of Android's VoxRecorder.
//
// An AVAudioEngine input tap feeds each buffer's level to the shared VoxDetector.
// Speech opens an AAC .m4a snippet (pre-roll first, so the first syllable survives the
// detector's attack window); trailing silence is held back and only ~250 ms is written.
// Half-duplex: yieldTurn() lets the current phrase finish, then holds the mic while the
// pack plays; releaseTurn() reopens it.
//
// "Paused" keeps the engine running but transmits nothing. iOS won't reliably restart
// mic capture from the background, and riders pause/resume from earbuds with the phone
// in a pocket, so the input stays up and frames are dropped instead.

import Foundation
import AVFAudio

enum PelotonMic: Equatable {
    case off, paused, listening, onAir, yielding
}

@MainActor
final class VoxEngine {
    var onSnippet: ((URL) -> Void)?
    var onMic: ((PelotonMic) -> Void)?
    var onLevel: ((Float) -> Void)?

    private let engine = AVAudioEngine()
    private let processor = VoxProcessor()
    private(set) var isRunning = false

    func start() -> Bool {
        guard !isRunning else { return true }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return false }

        processor.configure(format: format)
        processor.onSnippet = { [weak self] url in Task { @MainActor in self?.onSnippet?(url) } }
        processor.onMic = { [weak self] mic in Task { @MainActor in self?.onMic?(mic) } }
        processor.onLevel = { [weak self] v in Task { @MainActor in self?.onLevel?(v) } }

        let processor = self.processor
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            // The tap's buffer is only valid for this call; copy before hopping queues.
            guard let copy = buffer.deepCopy() else { return }
            processor.enqueue(copy)
        }
        do {
            engine.prepare()
            try engine.start()
        } catch {
            NSLog("PelotonCB vox engine start failed: \(error)")
            input.removeTap(onBus: 0)
            return false
        }
        isRunning = true
        processor.setPaused(false)
        return true
    }

    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        processor.shutdown()
        isRunning = false
        onLevel?(0)
    }

    /// Stop transmitting without closing the input (see file header).
    func setPaused(_ paused: Bool) {
        processor.setPaused(paused)
    }

    /// Half-duplex: returns once the mic is quiet (the rider's current phrase, if any,
    /// finishes and is sent first — bounded by the 10 s cap).
    func yieldTurn() async {
        guard isRunning else { return }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            processor.requestTurn { cont.resume() }
        }
    }

    func releaseTurn() {
        processor.releaseTurn()
    }
}

/// All audio-side state, confined to one serial queue.
private final class VoxProcessor: @unchecked Sendable {
    var onSnippet: ((URL) -> Void)?
    var onMic: ((PelotonMic) -> Void)?
    var onLevel: ((Float) -> Void)?

    private let queue = DispatchQueue(label: "pelotoncb.vox", qos: .userInitiated)
    private let detector = VoxDetector()
    private var format: AVAudioFormat?
    private var file: AVAudioFile?
    private var fileURL: URL?
    private var preroll: [AVAudioPCMBuffer] = []
    private var prerollMs = 0
    private var tail: [(AVAudioPCMBuffer, Int)] = []
    private var paused = true
    private var held = false
    private var wasHeld = false
    private var turnWaiters: [() -> Void] = []
    private var turnDeadline: Date?
    private var smoothed: Float = 0
    private var lastLevelPost = Date.distantPast

    private static let prerollTargetMs = 400
    private static let tailKeepMs = 250
    private static let maxYieldWait: TimeInterval = 12

    func configure(format: AVAudioFormat) {
        queue.sync {
            self.format = format
            detector.resetAll()
            preroll = []
            prerollMs = 0
            tail = []
            held = false
        }
    }

    func enqueue(_ buffer: AVAudioPCMBuffer) {
        queue.async { self.process(buffer) }
    }

    func setPaused(_ value: Bool) {
        queue.async {
            self.paused = value
            if value {
                self.abortSnippet()
                self.detector.reset()
                self.onMic?(.paused)
                self.onLevel?(0)
            } else {
                self.onMic?(self.held ? .yielding : .listening)
            }
        }
    }

    func requestTurn(_ done: @escaping () -> Void) {
        queue.async {
            if self.held || self.paused || !self.detector.capturing {
                // Paused or quiet: the turn is free right now.
                self.grantTurn()
                done()
                return
            }
            self.turnWaiters.append(done)
            self.turnDeadline = Date().addingTimeInterval(Self.maxYieldWait)
        }
    }

    func releaseTurn() {
        queue.async { self.held = false }
    }

    func shutdown() {
        queue.async {
            self.abortSnippet()
            self.paused = true
            self.grantTurn()
            self.held = false
        }
    }

    // MARK: Processing (queue only)

    private func process(_ buffer: AVAudioPCMBuffer) {
        guard let format, let channel = buffer.floatChannelData?[0] else { return }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }
        let ms = Int(Double(frames) / format.sampleRate * 1000)
        let level = VoxDetector.levelDbfs(UnsafeBufferPointer(start: channel, count: frames))

        // Meter: 0..1 over -60..-10 dBFS, fast attack / slow release, ~10 Hz.
        let target = Float(min(1, max(0, (level + 60) / 50)))
        smoothed = target > smoothed ? target : smoothed * 0.85 + target * 0.15
        if Date().timeIntervalSince(lastLevelPost) >= 0.1 {
            lastLevelPost = Date()
            onLevel?(paused || held ? 0 : smoothed)
        }

        if paused { return }
        if !turnWaiters.isEmpty,
           !detector.capturing || (turnDeadline.map { Date() > $0 } ?? false) {
            if detector.capturing { finishSnippet(send: true) }
            grantTurn()
        }
        if held {
            wasHeld = true
            return
        }
        if wasHeld {
            // Pack finished talking: whatever the mic heard meanwhile was them.
            wasHeld = false
            detector.reset()
            preroll = []
            prerollMs = 0
            onMic?(.listening)
        }

        preroll.append(buffer)
        prerollMs += ms
        while prerollMs - Self.frameMs(preroll[0], format) >= Self.prerollTargetMs {
            prerollMs -= Self.frameMs(preroll.removeFirst(), format)
        }

        switch detector.onFrame(levelDb: level, durationMs: ms) {
        case .start:
            openSnippet()
            preroll.forEach(write)
            preroll = []
            prerollMs = 0
            tail = []
            onMic?(.onAir)
        case .none:
            guard detector.capturing else { break }
            if detector.lastFrameWasSpeech {
                tail.forEach { write($0.0) }
                tail = []
                write(buffer)
            } else {
                tail.append((buffer, ms))
            }
        case .stopSend:
            var kept = 0
            for (b, bms) in tail where kept < Self.tailKeepMs {
                write(b)
                kept += bms
            }
            tail = []
            finishSnippet(send: true)
            if turnWaiters.isEmpty { onMic?(.listening) }
        case .stopDiscard:
            tail = []
            finishSnippet(send: false)
            if turnWaiters.isEmpty { onMic?(.listening) }
        case .split:
            tail.forEach { write($0.0) }
            tail = []
            write(buffer)
            finishSnippet(send: true)
            if turnWaiters.isEmpty {
                openSnippet()
            } else {
                grantTurn() // someone's waiting: don't start another 10 s
            }
        }
    }

    private func grantTurn() {
        if detector.capturing { finishSnippet(send: true) }
        detector.reset()
        preroll = []
        prerollMs = 0
        tail = []
        let wasAlreadyHeld = held
        held = true
        let waiters = turnWaiters
        turnWaiters = []
        turnDeadline = nil
        waiters.forEach { $0() }
        if !paused, !wasAlreadyHeld { onMic?(.yielding) }
    }

    private func openSnippet() {
        guard let format else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("snip_\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: Int(format.channelCount),
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        do {
            file = try AVAudioFile(
                forWriting: url,
                settings: settings,
                commonFormat: format.commonFormat,
                interleaved: format.isInterleaved
            )
            fileURL = url
        } catch {
            NSLog("PelotonCB snippet open failed: \(error)")
            file = nil
            fileURL = nil
        }
    }

    private func write(_ buffer: AVAudioPCMBuffer) {
        guard let file else { return }
        do {
            try file.write(from: buffer)
        } catch {
            NSLog("PelotonCB snippet write failed: \(error)")
        }
    }

    private func finishSnippet(send: Bool) {
        // Dropping the last reference finalizes the AAC file.
        file = nil
        guard let url = fileURL else { return }
        fileURL = nil
        if send {
            onSnippet?(url)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func abortSnippet() {
        tail = []
        finishSnippet(send: false)
    }

    private static func frameMs(_ b: AVAudioPCMBuffer, _ f: AVAudioFormat) -> Int {
        Int(Double(b.frameLength) / f.sampleRate * 1000)
    }
}

private extension AVAudioPCMBuffer {
    func deepCopy() -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameLength) else { return nil }
        copy.frameLength = frameLength
        let channels = Int(format.channelCount)
        let frames = Int(frameLength)
        if let src = floatChannelData, let dst = copy.floatChannelData {
            let stride = format.isInterleaved ? channels : 1
            let perChannel = format.isInterleaved ? 1 : channels
            for c in 0..<perChannel {
                dst[c].update(from: src[c], count: frames * stride)
            }
            return copy
        }
        return nil
    }
}
