// NowPlayingService.swift
// Registers as the system media player ("steering wheel hack", PROTOCOL §10):
//   - Play/Pause (⏯) → push-to-talk toggle
//   - Next Track (⏩) → skip current burst + stealth-mute its sender
// Publishes "Car Radio — live" to MPNowPlayingInfoCenter.

import Foundation
import MediaPlayer

@MainActor
final class NowPlayingService {
    var onPlayPause: (() -> Void)?
    var onNextTrack: (() -> Void)?

    private let commandCenter = MPRemoteCommandCenter.shared()
    private let infoCenter = MPNowPlayingInfoCenter.default()

    func activate() {
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.onPlayPause?()
            return .success
        }
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.onPlayPause?()
            return .success
        }
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.onPlayPause?()
            return .success
        }
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.onNextTrack?()
            return .success
        }
        // Not used; disable so the system doesn't show dead buttons.
        commandCenter.previousTrackCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = false

        updateNowPlaying(subtitle: nil)
    }

    func updateNowPlaying(subtitle: String?) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: "Car Radio — live",
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: 1.0,
        ]
        if let subtitle {
            info[MPMediaItemPropertyArtist] = subtitle
        }
        infoCenter.nowPlayingInfo = info
    }

    func deactivate() {
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.togglePlayPauseCommand.removeTarget(nil)
        commandCenter.nextTrackCommand.removeTarget(nil)
        infoCenter.nowPlayingInfo = nil
    }
}
