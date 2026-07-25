// DriveModeView.swift
// Drive Mode per PROTOCOL §13: eyes-up, hands-on-the-wheel.
//   - the whole screen is the tap-to-talk button
//   - swipe down anywhere = skip + stealth-mute
//   - dark only, giant type, ambient status via the density ring
//   - locks in at speed (handled by AppModel); an 8 s long-press on the
//     "passenger" chip unlocks

import SwiftUI
import UIKit

struct DriveModeView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                Spacer()
                DensityRing(
                    peerCount: model.peerCount,
                    isReceiving: model.isPlayingBurst,
                    isRecording: model.isRecording
                )
                .frame(width: 260, height: 260)
                Spacer()
                footer
            }
            .padding(24)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            model.toggleTalk()
        }
        .gesture(
            DragGesture(minimumDistance: 60)
                .onEnded { value in
                    if value.translation.height > 60,
                       abs(value.translation.height) > abs(value.translation.width) {
                        model.skipAndMute() // swipe down: skip + stealth-mute
                    }
                }
        )
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(model.handle.uppercased())
                .font(.system(size: 28, weight: .black, design: .rounded))
                .kerning(2)
                .foregroundStyle(.white.opacity(0.92))
            HStack(spacing: 8) {
                if model.isElasticMode {
                    Label("WIDE RANGE", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(.orange)
                }
                Text("\(Int(model.speedMps * GeoMath.mphPerMps)) MPH")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.top, 8)
    }

    private var footer: some View {
        VStack(spacing: 14) {
            statusLine
            Text(model.isRecording ? "TAP TO SEND" : "TAP ANYWHERE TO TALK")
                .font(.system(size: 17, weight: .heavy, design: .rounded))
                .kerning(1.5)
                .foregroundStyle(model.isRecording ? .red : .green)
            Text("SWIPE DOWN TO SKIP & MUTE")
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .kerning(1)
                .foregroundStyle(.tertiary)

            if model.currentSpeakerHandle != nil || model.lastSpeakerHandle != nil {
                Button("Report speaker") {
                    model.reportCurrentOrLast()
                }
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .tint(.secondary)
                .padding(.top, 2)
            }

            if model.driveLocked {
                PassengerChip {
                    model.passengerUnlock()
                }
                .padding(.top, 4)
            } else {
                Button("End trip") {
                    model.endTrip()
                }
                .font(.footnote)
                .tint(.secondary)
                .padding(.top, 4)
            }
        }
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var statusLine: some View {
        if model.isRecording {
            Label("ON AIR", systemImage: "mic.fill")
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(.red)
        } else if let speaker = model.currentSpeakerHandle {
            Label(speaker, systemImage: "speaker.wave.2.fill")
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(.green)
        } else if let last = model.lastSpeakerHandle {
            Text("Last heard: \(last)")
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
        } else {
            Text("Listening to the road…")
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
        }
    }
}

/// "I'm a passenger" — deliberate 8 s hold so a driver can't casually unlock.
private struct PassengerChip: View {
    let onUnlock: () -> Void
    @State private var holding = false

    var body: some View {
        Text(holding ? "KEEP HOLDING…" : "PASSENGER? HOLD 8 S")
            .font(.system(size: 11, weight: .semibold, design: .rounded))
            .kerning(1)
            .foregroundStyle(holding ? Color.orange : Color.white.opacity(0.35))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().stroke(Color.white.opacity(0.15)))
            .onLongPressGesture(minimumDuration: Constants.passengerHold, maximumDistance: 60) {
                onUnlock()
            } onPressingChanged: { pressing in
                holding = pressing
            }
    }
}
