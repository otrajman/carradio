// AudioSessionController.swift
// AVAudioSession modes per PROTOCOL §10:
//   - playback: .playback + .duckOthers + .interruptSpokenAudioAndMixWithOthers
//     (ducks music/nav to ~30 % while a burst or earcon plays)
//   - recording: .playAndRecord (+ duckOthers, bluetooth) while the mic is open
// Deactivation always notifies others so music ramps back up.

import Foundation
import AVFAudio

final class AudioSessionController {
    static let shared = AudioSessionController()

    private let session = AVAudioSession.sharedInstance()
    private(set) var isActive = false
    private(set) var mode: Mode = .idle

    enum Mode {
        case idle
        case playback
        case recording
    }

    private init() {}

    /// Ducking playback session (PROTOCOL §10).
    func beginPlayback() {
        configure(
            category: .playback,
            sessionMode: .spokenAudio,
            options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers]
        )
        mode = .playback
    }

    /// Mic-open session. `.playAndRecord` so earcons and monitoring still work.
    func beginRecording() {
        configure(
            category: .playAndRecord,
            sessionMode: .spokenAudio,
            options: [.duckOthers, .allowBluetooth, .defaultToSpeaker]
        )
        mode = .recording
    }

    /// Ends the current mode and lets other audio resume at full volume.
    func end() {
        guard isActive else { return }
        do {
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
            isActive = false
            mode = .idle
        } catch {
            NSLog("CarRadio audio session deactivate failed: \(error)")
        }
    }

    private func configure(category: AVAudioSession.Category,
                           sessionMode: AVAudioSession.Mode,
                           options: AVAudioSession.CategoryOptions) {
        do {
            try session.setCategory(category, mode: sessionMode, options: options)
            try session.setActive(true)
            isActive = true
        } catch {
            NSLog("CarRadio audio session activate failed: \(error)")
        }
    }
}
