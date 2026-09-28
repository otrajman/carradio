// RideView.swift
// In-ride screen for a handlebar-mounted phone: am I on air, who's talking, how many are
// with me. One giant target pauses/resumes the mic; skip/report/leave are secondary and
// also reachable from earbud buttons.

import SwiftUI

struct RideView: View {
    @EnvironmentObject private var model: PelotonModel
    @State private var sharing: String?

    var body: some View {
        VStack(spacing: 0) {
            header
            ZStack {
                VoxDial(mic: model.mic, level: model.micLevel, playing: model.isPlaying)
                    .padding(.horizontal, 24)
                centerLabel
            }
            .frame(maxHeight: .infinity)

            if let status = model.status {
                Text(status)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(PelotonPalette.signal)
                    .padding(.bottom, 8)
            }

            let paused = model.mic == .paused
            BigButton(
                label: paused ? "RESUME MIC" : "PAUSE MIC",
                background: paused ? PelotonPalette.signal : PelotonPalette.ink,
                foreground: paused ? PelotonPalette.onSignal : PelotonPalette.background,
                height: 112,
                fontSize: 30,
                action: model.togglePause
            )

            let canAct = model.isPlaying || model.lastSpeakerHandle != nil
            HStack(spacing: 12) {
                BigButton(label: "SKIP + MUTE", background: PelotonPalette.surface,
                          foreground: PelotonPalette.ink, border: PelotonPalette.line,
                          fontSize: 16, enabled: canAct, action: model.skipAndMute)
                BigButton(label: "REPORT", background: PelotonPalette.surface,
                          foreground: PelotonPalette.ink, border: PelotonPalette.line,
                          fontSize: 16, enabled: canAct, action: model.report)
                BigButton(label: "LEAVE", background: PelotonPalette.surface,
                          foreground: PelotonPalette.muted, border: PelotonPalette.line,
                          fontSize: 16, action: model.leaveRide)
            }
            .padding(.top, 12)

            Text("You're \(model.handle.isEmpty ? "…" : model.handle) · \(model.snippetsSent) sent")
                .font(.system(size: 13))
                .foregroundStyle(PelotonPalette.muted)
                .padding(.top, 10)
            Text("Earbud tap: pause · next: skip + mute")
                .font(.system(size: 12))
                .foregroundStyle(PelotonPalette.muted.opacity(0.7))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true } // handlebar mount
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .sheet(item: Binding(
            get: { sharing.map(ShareCode.init) },
            set: { sharing = $0?.code }
        )) { item in
            ShareSheet(text: "Ride with us on PelotonCB — pack code \(item.code)")
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Button { sharing = model.packCode } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Eyebrow(text: model.packCode != nil ? "Pack · tap to share" : "Open road · 500 m")
                    Text(model.packCode ?? "ANY RIDER NEARBY")
                        .font(.system(size: 24, weight: .black,
                                      design: model.packCode != nil ? .monospaced : .default))
                        .foregroundStyle(PelotonPalette.ink)
                        .lineLimit(1)
                }
            }
            .buttonStyle(.plain)
            .disabled(model.packCode == nil)
            Spacer()
            VStack(alignment: .trailing, spacing: 0) {
                Text("\(model.riderCount)")
                    .font(.system(size: 40, weight: .black))
                    .foregroundStyle(model.riderCount > 0 ? PelotonPalette.pack : PelotonPalette.muted)
                Eyebrow(text: model.riderCount == 1 ? "rider" : "riders")
            }
        }
    }

    private var centerLabel: some View {
        let (label, color): (String, Color) = {
            if model.isPlaying || model.mic == .yielding { return ("INCOMING", PelotonPalette.pack) }
            switch model.mic {
            case .onAir: return ("ON AIR", PelotonPalette.signal)
            case .listening: return ("LISTENING", PelotonPalette.ink)
            case .paused: return ("MIC PAUSED", PelotonPalette.muted)
            default: return ("CONNECTING", PelotonPalette.muted)
            }
        }()
        let sub: String = {
            if model.isPlaying, let s = model.speakerHandle { return s }
            switch model.mic {
            case .onAir: return "the pack hears you"
            case .listening: return "just talk"
            case .paused: return "you still hear the pack"
            default: return " "
            }
        }()
        return VStack(spacing: 2) {
            Text(label).font(.system(size: 30, weight: .black)).kerning(1).foregroundStyle(color)
            Text(sub).font(.system(size: 17, weight: .semibold)).foregroundStyle(PelotonPalette.muted)
        }
    }
}

/// 60 radial ticks: lit by live mic level (orange on air, ink listening); a green sweep
/// circles while the pack plays; dim when paused.
struct VoxDial: View {
    let mic: PelotonMic
    let level: Float
    let playing: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !(playing || mic == .yielding))) { context in
            Canvas { ctx, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let outer = min(size.width, size.height) / 2
                let inner = outer * 0.80
                let ticks = 60
                let receiving = playing || mic == .yielding
                let head = Int(context.date.timeIntervalSinceReferenceDate / 1.4 * Double(ticks)) % ticks
                let lit = Int(level * Float(ticks))
                let active: Color = receiving ? PelotonPalette.pack
                    : mic == .onAir ? PelotonPalette.signal
                    : mic == .listening ? PelotonPalette.ink : PelotonPalette.muted

                if mic == .onAir {
                    let r = inner * 0.86
                    ctx.fill(
                        Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)),
                        with: .color(PelotonPalette.signal.opacity(0.12 + 0.25 * Double(level)))
                    )
                }
                for i in 0..<ticks {
                    let angle = Double(i) / Double(ticks) * 2 * .pi - .pi / 2
                    let on: Bool
                    if receiving {
                        on = (head - i + ticks) % ticks < 14
                    } else if mic == .paused || mic == .off {
                        on = false
                    } else {
                        on = i < lit
                    }
                    let major = i % 5 == 0
                    let r0 = major ? inner * 0.94 : inner
                    var p = Path()
                    p.move(to: CGPoint(x: center.x + cos(angle) * r0, y: center.y + sin(angle) * r0))
                    p.addLine(to: CGPoint(x: center.x + cos(angle) * outer, y: center.y + sin(angle) * outer))
                    ctx.stroke(
                        p,
                        with: .color(on ? active : PelotonPalette.line),
                        style: StrokeStyle(lineWidth: major ? outer * 0.035 : outer * 0.022, lineCap: .round)
                    )
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.linear(duration: 0.09), value: level)
    }
}
