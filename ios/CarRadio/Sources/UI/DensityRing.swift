// DensityRing.swift
// The radar: a pulsing circle whose glow reflects network density and state —
// soft green while receiving, soft red while recording, dim otherwise.
// No maps, ever.

import SwiftUI

struct DensityRing: View {
    let peerCount: Int
    let isReceiving: Bool
    let isRecording: Bool

    @State private var pulse = false

    private var ringColor: Color {
        if isRecording { return .red }
        if isReceiving { return .green }
        return Color(white: 0.35)
    }

    /// More peers → stronger idle glow.
    private var glowOpacity: Double {
        let base = 0.25 + min(Double(peerCount), 8) * 0.06
        return (isReceiving || isRecording) ? 0.9 : base
    }

    var body: some View {
        ZStack {
            // Outer pulse
            Circle()
                .stroke(ringColor.opacity(glowOpacity * 0.5), lineWidth: 2)
                .scaleEffect(pulse ? 1.25 : 0.95)
                .opacity(pulse ? 0.1 : 0.8)

            // Main ring
            Circle()
                .stroke(ringColor.opacity(glowOpacity), lineWidth: 6)
                .shadow(color: ringColor.opacity(glowOpacity), radius: pulse ? 24 : 10)
                .scaleEffect(pulse ? 1.03 : 0.97)

            // Core
            Circle()
                .fill(ringColor.opacity(glowOpacity * 0.25))
                .scaleEffect(0.85)

            VStack(spacing: 4) {
                Text("\(peerCount)")
                    .font(.system(size: 64, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.92))
                Text(peerCount == 1 ? "DRIVER NEARBY" : "DRIVERS NEARBY")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .kerning(2)
                    .foregroundStyle(.secondary)
            }
        }
        .animation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true), value: pulse)
        .onAppear { pulse = true }
    }
}
