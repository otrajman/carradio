// EarconPlayer.swift
// The six shared earcons, synthesized as PCM exactly per PROTOCOL §9 —
// no audio assets. AVAudioEngine + AVAudioPlayerNode.
//
// | cue        | spec                                                        |
// | incoming   | 60 ms sine burst 880→440 Hz                                 |
// | micOpen    | two 80 ms sines, 520 Hz then 780 Hz                         |
// | sent       | 300 ms filtered noise sweep 2 kHz→300 Hz, fading            |
// | muted      | 30 ms sine at 180 Hz                                        |
// | system     | three 90 ms sines 660/830/990 Hz, 70 ms gaps                |
// | breadcrumb | incoming pop twice, 120 ms apart (start to start)           |

import Foundation
import AVFAudio

enum Earcon: CaseIterable {
    case incoming
    case micOpen
    case sent
    case muted
    case system
    case breadcrumb
}

@MainActor
final class EarconPlayer {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let sampleRate: Double = 44_100
    private let format: AVAudioFormat
    private var buffers: [Earcon: AVAudioPCMBuffer] = [:]

    init() {
        format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        // Slightly forward/center; mono into the main mixer.
        player.pan = 0.0
        for earcon in Earcon.allCases {
            buffers[earcon] = makeBuffer(for: earcon)
        }
    }

    /// Plays an earcon and returns when it has finished sounding.
    /// The caller is responsible for having an active audio session.
    func play(_ earcon: Earcon) async {
        guard let buffer = buffers[earcon] else { return }
        do {
            if !engine.isRunning {
                try engine.start()
            }
        } catch {
            NSLog("CarRadio earcon engine start failed: \(error)")
            return
        }
        if !player.isPlaying {
            player.play()
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            player.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { _ in
                continuation.resume()
            }
        }
    }

    func stopEngine() {
        player.stop()
        engine.stop()
    }

    // MARK: - Synthesis

    private func makeBuffer(for earcon: Earcon) -> AVAudioPCMBuffer? {
        let samples: [Float]
        switch earcon {
        case .incoming:
            samples = pop()
        case .micOpen:
            samples = tone(frequency: 520, duration: 0.080) + tone(frequency: 780, duration: 0.080)
        case .sent:
            samples = noiseSweep(duration: 0.300, startCutoff: 2_000, endCutoff: 300)
        case .muted:
            samples = tone(frequency: 180, duration: 0.030)
        case .system:
            let gap = silence(duration: 0.070)
            samples = tone(frequency: 660, duration: 0.090) + gap
                + tone(frequency: 830, duration: 0.090) + gap
                + tone(frequency: 990, duration: 0.090)
        case .breadcrumb:
            let p = pop() // 60 ms
            samples = p + silence(duration: 0.060) + p // starts 120 ms apart
        }
        return buffer(from: samples)
    }

    /// The "incoming" pop: 60 ms sine sweep 880 → 440 Hz.
    private func pop() -> [Float] {
        sweep(duration: 0.060, startFrequency: 880, endFrequency: 440)
    }

    private func silence(duration: Double) -> [Float] {
        [Float](repeating: 0, count: Int(duration * sampleRate))
    }

    /// Fixed-frequency sine with a 5 ms attack/release envelope.
    private func tone(frequency: Double, duration: Double, amplitude: Float = 0.5) -> [Float] {
        let count = Int(duration * sampleRate)
        var out = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let t = Double(i) / sampleRate
            out[i] = Float(sin(2 * .pi * frequency * t)) * amplitude * envelope(i, count)
        }
        return out
    }

    /// Linear frequency sweep with phase accumulation (click-free).
    private func sweep(duration: Double, startFrequency: Double, endFrequency: Double, amplitude: Float = 0.5) -> [Float] {
        let count = Int(duration * sampleRate)
        var out = [Float](repeating: 0, count: count)
        var phase = 0.0
        for i in 0..<count {
            let progress = Double(i) / Double(count)
            let frequency = startFrequency + (endFrequency - startFrequency) * progress
            phase += 2 * .pi * frequency / sampleRate
            out[i] = Float(sin(phase)) * amplitude * envelope(i, count)
        }
        return out
    }

    /// White noise through a one-pole lowpass whose cutoff sweeps down,
    /// with a linear fade-out ("whoosh").
    private func noiseSweep(duration: Double, startCutoff: Double, endCutoff: Double, amplitude: Float = 0.4) -> [Float] {
        let count = Int(duration * sampleRate)
        var out = [Float](repeating: 0, count: count)
        var state: Float = 0
        var rng = SystemRandomNumberGenerator()
        for i in 0..<count {
            let progress = Double(i) / Double(count)
            let cutoff = startCutoff + (endCutoff - startCutoff) * progress
            // one-pole coefficient: a = e^(-2π·fc/fs)
            let a = Float(exp(-2 * .pi * cutoff / sampleRate))
            let white = Float.random(in: -1...1, using: &rng)
            state = a * state + (1 - a) * white
            let fade = Float(1 - progress)
            out[i] = state * amplitude * fade * envelope(i, count)
        }
        return out
    }

    /// 5 ms linear attack/release to avoid clicks.
    private func envelope(_ i: Int, _ count: Int) -> Float {
        let ramp = min(count / 2, Int(0.005 * sampleRate))
        guard ramp > 0 else { return 1 }
        if i < ramp { return Float(i) / Float(ramp) }
        if i >= count - ramp { return Float(count - i) / Float(ramp) }
        return 1
    }

    private func buffer(from samples: [Float]) -> AVAudioPCMBuffer? {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else {
            return nil
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        if let channel = buffer.floatChannelData?[0] {
            samples.withUnsafeBufferPointer { src in
                channel.update(from: src.baseAddress!, count: samples.count)
            }
        }
        return buffer
    }
}
