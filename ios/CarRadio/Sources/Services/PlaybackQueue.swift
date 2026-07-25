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

            // Age drop at dequeue (PROTOCOL §5.6) — breadcrumbs exempt (§6).
            if !item.isBreadcrumb {
                let created = WireDate.date(from: item.payload.createdAt) ?? item.enqueuedAt
                if Date().timeIntervalSince(created) > Constants.burstMaxAgeSeconds {
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
            } else {
                await earcons.play(.incoming)
            }

            // Then the burst body.
            if let text = item.payload.text, item.payload.isSystem || item.payload.audioPath == nil {
                await speak(text: text, item: item)
            } else if let path = item.payload.audioPath {
                await playVoice(path: path)
            }

            lastSpeakerHandle = item.payload.handle
            lastFinishedItem = item
            currentItem = nil
            isPlaying = false
            currentHandle = nil
        }
        processing = false
        AudioSessionController.shared.end()
    }

    // MARK: Voice bursts

    private func playVoice(path: String) async {
        guard let url = Constants.publicAudioURL(for: path) else { return }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                NSLog("CarRadio playback: HTTP \(http.statusCode) for \(path)")
                return
            }
            let player = try AVAudioPlayer(data: data)
            player.delegate = self
            audioPlayer = player
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                finishContinuation = continuation
                if !player.play() {
                    resumeFinish()
                }
            }
            audioPlayer = nil
        } catch {
            NSLog("CarRadio playback failed for \(path): \(error)")
        }
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
