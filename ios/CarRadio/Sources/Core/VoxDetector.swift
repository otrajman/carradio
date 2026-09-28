import Foundation

/// PROTOCOL §16 voice-activated transmit (VOX). Pure state machine, twin of the Kotlin
/// `VoxDetector`: the platform feeds one level per audio frame (dBFS, any frame length)
/// and acts on the returned `Event`.
///
/// - Noise floor = minimum frame level over a sliding window. Speech has inter-word dips,
///   so the minimum tracks steady wind/road noise and ignores the rider's voice.
/// - Onset: level ≥ floor + onsetMargin (and ≥ minOnset) for `attackMs`. The platform keeps
///   a pre-roll buffer longer than the attack so the first syllable isn't clipped.
/// - Release: `hangoverMs` below floor + sustainMargin ends the snippet; snippets with less
///   than `minSpeechMs` of speech are discarded (bumps, clicks).
/// - Snippets are capped at `maxSnippetMs` (the §4 10 s cap): `.split` sends the current
///   snippet and keeps capturing into a fresh one.
public final class VoxDetector {

    public struct Config {
        public var warmupMs = 600
        public var floorWindowMs = 2_000
        public var onsetMarginDb = 14.0
        public var sustainMarginDb = 8.0
        public var minOnsetDb = -50.0
        public var attackMs = 120
        public var hangoverMs = 1_100
        public var minSpeechMs = 250
        public var maxSnippetMs = 10_000

        public init() {}
    }

    public enum Event: Equatable {
        case none
        /// Speech onset: open a snippet, write the pre-roll first.
        case start
        /// Snippet ended with enough speech: finalize and send.
        case stopSend
        /// Snippet ended without enough speech: throw it away.
        case stopDiscard
        /// Hit the length cap mid-speech: send this snippet, keep capturing into a new one.
        case split
    }

    public static let silenceDb = -100.0

    public private(set) var capturing = false
    /// Whether the most recent frame counted as speech (for tail trimming).
    public private(set) var lastFrameWasSpeech = false

    public var noiseFloorDb: Double {
        window.map(\.level).min() ?? Self.silenceDb
    }

    private let config: Config
    private var window: [(ms: Int, level: Double)] = []
    private var windowMs = 0
    private var observedMs = 0
    private var aboveMs = 0
    private var snippetMs = 0
    private var speechMs = 0
    private var silenceMs = 0

    public init(config: Config = Config()) {
        self.config = config
    }

    public func onFrame(levelDb: Double, durationMs: Int) -> Event {
        // Floor from the window *before* this frame, so a loud onset can't raise its own bar.
        let floor = window.isEmpty ? levelDb : noiseFloorDb
        pushWindow(levelDb, durationMs)
        observedMs += durationMs

        if !capturing {
            lastFrameWasSpeech = false
            guard observedMs >= config.warmupMs else { return .none }
            if levelDb >= max(floor + config.onsetMarginDb, config.minOnsetDb) {
                aboveMs += durationMs
                if aboveMs >= config.attackMs {
                    capturing = true
                    snippetMs = aboveMs
                    speechMs = aboveMs
                    silenceMs = 0
                    aboveMs = 0
                    lastFrameWasSpeech = true
                    return .start
                }
            } else {
                aboveMs = 0
            }
            return .none
        }

        snippetMs += durationMs
        let speaking = levelDb >= max(floor + config.sustainMarginDb, config.minOnsetDb - 6)
        lastFrameWasSpeech = speaking
        if speaking {
            speechMs += durationMs
            silenceMs = 0
        } else {
            silenceMs += durationMs
        }

        if silenceMs >= config.hangoverMs {
            let enough = speechMs >= config.minSpeechMs
            endSnippet()
            return enough ? .stopSend : .stopDiscard
        }
        if snippetMs >= config.maxSnippetMs {
            snippetMs = 0
            speechMs = 0
            return .split
        }
        return .none
    }

    /// Abandon any snippet in progress (pause / half-duplex hold). The floor is kept.
    public func reset() {
        endSnippet()
        aboveMs = 0
        lastFrameWasSpeech = false
    }

    /// Full reset incl. noise floor and warmup (mic reopened).
    public func resetAll() {
        reset()
        window.removeAll()
        windowMs = 0
        observedMs = 0
    }

    private func endSnippet() {
        capturing = false
        snippetMs = 0
        speechMs = 0
        silenceMs = 0
    }

    private func pushWindow(_ level: Double, _ ms: Int) {
        window.append((ms, level))
        windowMs += ms
        while windowMs - window[0].ms >= config.floorWindowMs {
            windowMs -= window.removeFirst().ms
        }
    }

    /// dBFS of a float frame's RMS (samples in [-1, 1]); `silenceDb` for digital silence.
    public static func levelDbfs(_ samples: UnsafeBufferPointer<Float>) -> Double {
        guard !samples.isEmpty else { return silenceDb }
        var sum: Double = 0
        for s in samples { sum += Double(s) * Double(s) }
        let rms = (sum / Double(samples.count)).squareRoot()
        guard rms > 1.0 / 32768.0 else { return silenceDb }
        return max(silenceDb, 20 * log10(rms))
    }
}
