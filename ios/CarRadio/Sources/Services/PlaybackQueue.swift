// PlaybackQueue.swift
// FIFO playback per PROTOCOL §5.6:
//   - never overlap two bursts
//   - drop live bursts older than 60 s at dequeue time (breadcrumbs exempt)
//   - earcon precedes every burst (incoming / breadcrumb / system triple-chime)
//   - system (text) bursts are spoken with AVSpeechSynthesizer
//   - audio ducks other apps while playing (AudioSessionController)

import Foundation
import AVFAudio

@MainActor
final class PlaybackQueue: NSObject, ObservableObject {
    struct Item {
        let payload: BurstPayload
        let isBreadcrumb: Bool
        let enqueuedAt: Date

        init(payload: BurstPayload, isBreadcrumb: Bool = false, enqueuedAt: Date = Date()) {
            self.payload = payload
            self.isBreadcrumb = isBreadcrumb
            self.enqueuedAt = enqueuedAt
        }
    }

    @Published private(set) var isPlaying = false
    @Published private(set) var currentHandle: String?
    @Published private(set) var lastSpeakerHandle: String?

    /// Called when an item actually starts playing (breadcrumb bookkeeping).
    var onItemStarted: ((Item) -> Void)?
    /// Awaited before each item may sound. Car Radio plays immediately; PelotonCB's
    /// half-duplex VOX (PROTOCOL §16) waits here for the rider's open snippet to finish.
    var awaitTurn: (() async -> Void)?
    /// Called after each item finishes (played, skipped, or dropped mid-play).
    var onItemFinished: ((Item) -> Void)?

    /// Start downloading a burst's audio when it is queued instead of when its turn comes
    /// (PelotonCB chunked phrases).
    var prefetch = false
    /// PROTOCOL §16.4: a voice burst from the sender who finished playing less than this
    /// long ago is the next chunk of the same phrase and plays without an earcon.
    /// 0 = every burst gets its earcon (Car Radio).
    var continuationGap: TimeInterval = 0

    private var prefetched: [String: Task<Data?, Never>] = [:]
    private var lastVoiceTripID: String?
    private var lastVoiceEndedAt = Date.distantPast

    private var queue: [Item] = []
    private var processing = false
    private var currentItem: Item?
    private var lastFinishedItem: Item?

    private let earcons: EarconPlayer
    private var audioPlayer: AVAudioPlayer?
    private let synthesizer = AVSpeechSynthesizer()
    private var finishContinuation: CheckedContinuation<Void, Never>?

    init(earcons: EarconPlayer) {
        self.earcons = earcons
        super.init()
        synthesizer.delegate = self
    }

    // MARK: Public

    func enqueue(_ item: Item) {
        if prefetch, let path = item.payload.audioPath, let url = Constants.publicAudioURL(for: path) {
            prefetched[item.payload.messageID] = Task { await Self.fetch(url) }
        }
        queue.append(item)
        processIfNeeded()
    }

    /// Stops the current burst (if any) and returns its payload so the caller
    /// can stealth-mute the sender. Does not touch the rest of the queue.
    @discardableResult
    func skipCurrent() -> BurstPayload? {
        guard let item = currentItem else { return nil }
        audioPlayer?.stop()
        synthesizer.stopSpeaking(at: .immediate)
        resumeFinish()
        return item.payload
    }

    /// Replays the last completed burst ("hey radio, repeat").
    func repeatLast() {
        guard let last = lastFinishedItem else { return }
        queue.insert(Item(payload: last.payload, isBreadcrumb: last.isBreadcrumb), at: 0)
        processIfNeeded()
    }

    /// Full stop: drops the queue and silences anything in flight (end of trip).
    func stopAll() {
        queue.removeAll()
        prefetched.values.forEach { $0.cancel() }
        prefetched.removeAll()
        audioPlayer?.stop()
        synthesizer.stopSpeaking(at: .immediate)
        resumeFinish()
    }

    var isIdle: Bool { !processing && queue.isEmpty }

    /// The payload most recently played to completion (for "mute the last
    /// speaker" when nothing is currently playing).
    var lastFinishedPayload: BurstPayload? { lastFinishedItem?.payload }

    // MARK: Processing loop

    private func processIfNeeded() {
        guard !processing else { return }
        processing = true
        Task { [weak self] in
            await self?.drainQueue()
        }
    }

    private func drainQueue() async {
        AudioSessionController.shared.beginPlayback()
        while !queue.isEmpty {
            let item = queue.removeFirst()
            await awaitTurn?()

            // Age drop at dequeue (PROTOCOL §5.6) — breadcrumbs exempt (§6).
            if !item.isBreadcrumb {
                let created = WireDate.date(from: item.payload.createdAt) ?? item.enqueuedAt
                if Date().timeIntervalSince(created) > Constants.burstMaxAgeSeconds {
                    prefetched.removeValue(forKey: item.payload.messageID)?.cancel()
                    continue
                }
            }

            currentItem = item
            isPlaying = true
            currentHandle = item.payload.handle
            onItemStarted?(item)

            // Earcon first.
            if item.payload.isSystem {
                await earcons.play(.system) // triple-chime always precedes system playback
            } else if item.isBreadcrumb {
                await earcons.play(.breadcrumb)
            } else if item.payload.tripID != lastVoiceTripID
                        || Date().timeIntervalSince(lastVoiceEndedAt) > continuationGap {
                await earcons.play(.incoming)
            }

            // Then the burst body. §12/§17: system bursts play their server-rendered AI
            // voice when present and fall back to on-device TTS of the same text.
            if item.payload.isSystem {
                var played = false
                if let path = item.payload.audioPath { played = await playVoice(path: path, item: item) }
                if !played, let text = item.payload.text { await speak(text: text, item: item) }
            } else if let path = item.payload.audioPath {
                await playVoice(path: path, item: item)
                if !item.isBreadcrumb {
                    lastVoiceTripID = item.payload.tripID
                    lastVoiceEndedAt = Date()
                }
            } else if let text = item.payload.text {
                await speak(text: text, item: item)
            }

            lastSpeakerHandle = item.payload.handle
            lastFinishedItem = item
            currentItem = nil
            isPlaying = false
            currentHandle = nil
            onItemFinished?(item)
        }
        processing = false
        AudioSessionController.shared.end()
    }

    // MARK: Voice bursts

    /// Plays a stored burst. Returns false if it could not be fetched or started.
    @discardableResult
    private func playVoice(path: String, item: Item) async -> Bool {
        guard let url = Constants.publicAudioURL(for: path) else { return false }
        do {
            var fetched: Data?
            if let task = prefetched.removeValue(forKey: item.payload.messageID) {
                fetched = await task.value
            }
            if fetched == nil { fetched = await Self.fetch(url) }
            guard let data = fetched else {
                NSLog("CarRadio playback: could not fetch \(path)")
                return false
            }
            let player = try AVAudioPlayer(data: data)
            player.delegate = self
            audioPlayer = player
            var started = true
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                finishContinuation = continuation
                if !player.play() {
                    started = false
                    resumeFinish()
                }
            }
            audioPlayer = nil
            return started
        } catch {
            NSLog("CarRadio playback failed for \(path): \(error)")
            return false
        }
    }

    private static func fetch(_ url: URL) async -> Data? {
        guard let (data, response) = try? await URLSession.shared.data(from: url) else { return nil }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { return nil }
        return data
    }

    // MARK: System / text bursts

    private func speak(text: String, item: Item) async {
        var spoken = text
        if item.isBreadcrumb,
           let created = WireDate.date(from: item.payload.createdAt),
           Date().timeIntervalSince(created) > Constants.breadcrumbEarlierThreshold {
            spoken = "Earlier here: " + spoken
        }
        let utterance = AVSpeechUtterance(string: spoken)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            finishContinuation = continuation
            synthesizer.speak(utterance)
        }
    }

    private func resumeFinish() {
        finishContinuation?.resume()
        finishContinuation = nil
    }
}

// MARK: - AVAudioPlayerDelegate

extension PlaybackQueue: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.resumeFinish()
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in
            self.resumeFinish()
        }
    }
}

// MARK: - AVSpeechSynthesizerDelegate

extension PlaybackQueue: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.resumeFinish()
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.resumeFinish()
        }
    }
}
