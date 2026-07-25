// WakeWordService.swift
// "Hey Radio" wake word via SFSpeechRecognizer (feature-flagged, PROTOCOL §0):
//   - "hey radio, mute"    → mute the current/last speaker
//   - "hey radio, report"  → report the current/last speaker (§8)
//   - "hey radio, repeat"  → replay the last burst
//   - "hey radio" + speech → record a burst until an endpointing pause
// Prefers on-device recognition when available; degrades gracefully (service
// simply stays off) when speech permissions or the recognizer are unavailable.
//
// Known limitation (documented in HANDOFF.md): the recognizer shares the mic
// with the burst recorder, so listening is suspended while a burst records
// and while audio plays back.

import Foundation
import AVFAudio
import Speech

@MainActor
final class WakeWordService: NSObject {
    enum Command {
        case mute
        case report
        case repeatLast
        case startTalking
    }

    var onCommand: ((Command) -> Void)?

    private(set) var isAvailable = false
    private(set) var isListening = false

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var restartTimer: Timer?
    private var lastTriggerAt: Date = .distantPast

    func requestAuthorizationAndStart() {
        guard Constants.featureWakeWord else { return }
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            Task { @MainActor in
                guard let self else { return }
                guard status == .authorized, let recognizer = self.recognizer, recognizer.isAvailable else {
                    NSLog("CarRadio wake word unavailable (status \(status.rawValue)) — degrading gracefully")
                    self.isAvailable = false
                    return
                }
                self.isAvailable = true
                self.startListening()
            }
        }
    }

    func startListening() {
        guard isAvailable, !isListening else { return }
        do {
            try beginRecognition()
            isListening = true
            // Apple caps recognition sessions (~1 min); restart proactively.
            restartTimer = Timer.scheduledTimer(withTimeInterval: 50, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.restartRecognition()
                }
            }
        } catch {
            NSLog("CarRadio wake word start failed: \(error)")
            isListening = false
        }
    }

    func stopListening() {
        restartTimer?.invalidate()
        restartTimer = nil
        endRecognition()
        isListening = false
    }

    // MARK: Recognition plumbing

    private func beginRecognition() throws {
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer?.supportsOnDeviceRecognition == true {
            request.requiresOnDeviceRecognition = true
        }
        self.request = request

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        try engine.start()

        task = recognizer?.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.handleTranscript(result.bestTranscription.formattedString.lowercased())
                }
                if error != nil {
                    // Session ended (timeout, route change, …) — restart lazily.
                    self.endRecognition()
                    if self.isListening {
                        try? self.beginRecognition()
                    }
                }
            }
        }
    }

    private func endRecognition() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        request?.endAudio()
        task?.cancel()
        task = nil
        request = nil
    }

    private func restartRecognition() {
        guard isListening else { return }
        endRecognition()
        try? beginRecognition()
    }

    // MARK: Trigger parsing

    private func handleTranscript(_ transcript: String) {
        // Debounce: one trigger per 3 s, and only look at text AFTER the last
        // occurrence of the wake phrase.
        guard Date().timeIntervalSince(lastTriggerAt) > 3 else { return }
        guard let range = transcript.range(of: "hey radio", options: .backwards) else { return }
        let after = transcript[range.upperBound...]
            .trimmingCharacters(in: CharacterSet.alphanumerics.inverted.union(.whitespaces))

        if after.hasPrefix("mute") {
            trigger(.mute)
        } else if after.hasPrefix("report") {
            trigger(.report)
        } else if after.hasPrefix("repeat") {
            trigger(.repeatLast)
        } else if after.isEmpty {
            // Bare "hey radio" → open the mic and record until a pause.
            trigger(.startTalking)
        }
        // Any other trailing words: wait — the user may still be saying
        // "mute"/"repeat"; the debounce window handles re-parsing.
    }

    private func trigger(_ command: Command) {
        lastTriggerAt = Date()
        // Recognition and burst recording can't share the mic; stop first.
        stopListening()
        onCommand?(command)
    }
}
