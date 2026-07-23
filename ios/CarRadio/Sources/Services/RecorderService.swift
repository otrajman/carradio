// RecorderService.swift
// Push-to-talk recording: AVAudioRecorder, AAC (kAudioFormatMPEG4AAC) mono
// ~24 kbps .m4a, hard 10 s cap (PROTOCOL §4). Optional silence endpointing for
// wake-word-initiated bursts.

import Foundation
import AVFAudio

@MainActor
final class RecorderService: NSObject, ObservableObject {
    @Published private(set) var isRecording = false

    /// Called with the finished file when recording stops (manually, at the
    /// 10 s cap, or by silence endpointing). Nil URL means the take failed.
    var onFinished: ((URL?) -> Void)?

    private var recorder: AVAudioRecorder?
    private var meterTimer: Timer?
    private var silenceStarted: Date?
    private var endpointOnSilence = false

    /// Average-power threshold (dBFS) below which we count "silence".
    private let silenceThresholdDb: Float = -45
    /// Silence run that ends a wake-word take.
    private let silenceDuration: TimeInterval = 1.5

    private var fileURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("burst.m4a")
    }

    /// Starts a take. `endpointOnSilence` is used for wake-word bursts:
    /// recording auto-stops after ~1.5 s of silence.
    func start(endpointOnSilence: Bool = false) -> Bool {
        guard !isRecording else { return true }
        self.endpointOnSilence = endpointOnSilence

        AudioSessionController.shared.beginRecording()

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 24_000.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 24_000,
        ]
        do {
            try? FileManager.default.removeItem(at: fileURL)
            let recorder = try AVAudioRecorder(url: fileURL, settings: settings)
            recorder.delegate = self
            recorder.isMeteringEnabled = endpointOnSilence
            self.recorder = recorder
            guard recorder.record(forDuration: Constants.maxBurstSeconds) else {
                cleanupAfterStop()
                return false
            }
            isRecording = true
            if endpointOnSilence {
                startMetering()
            }
            return true
        } catch {
            NSLog("CarRadio recorder start failed: \(error)")
            cleanupAfterStop()
            return false
        }
    }

    /// Manually ends the take; `onFinished` fires from the delegate.
    func stop() {
        recorder?.stop()
    }

    /// Aborts without sending.
    func cancel() {
        recorder?.delegate = nil
        recorder?.stop()
        recorder?.deleteRecording()
        cleanupAfterStop()
        isRecording = false
    }

    // MARK: Silence endpointing (wake-word takes)

    private func startMetering() {
        silenceStarted = nil
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.checkMeter()
            }
        }
    }

    private func checkMeter() {
        guard let recorder, recorder.isRecording else { return }
        recorder.updateMeters()
        let power = recorder.averagePower(forChannel: 0)
        if power < silenceThresholdDb {
            if let started = silenceStarted {
                if Date().timeIntervalSince(started) >= silenceDuration,
                   recorder.currentTime > 0.8 { // don't cut off instantly
                    stop()
                }
            } else {
                silenceStarted = Date()
            }
        } else {
            silenceStarted = nil
        }
    }

    private func cleanupAfterStop() {
        meterTimer?.invalidate()
        meterTimer = nil
        silenceStarted = nil
        recorder = nil
        AudioSessionController.shared.end()
    }
}

extension RecorderService: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            let url = flag ? recorder.url : nil
            self.cleanupAfterStop()
            self.isRecording = false
            self.onFinished?(url)
        }
    }

    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        Task { @MainActor in
            self.cleanupAfterStop()
            self.isRecording = false
            self.onFinished?(nil)
        }
    }
}
