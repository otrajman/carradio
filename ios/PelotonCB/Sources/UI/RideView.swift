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

    private static let ticks = 60
    private static let sweepLength = 14

    private var receiving: Bool { playing || mic == .yielding }

    private var activeColor: Color {
        if receiving { return PelotonPalette.pack }
        switch mic {
        case .onAir: return PelotonPalette.signal
        case .listening: return PelotonPalette.ink
        default: return PelotonPalette.muted
        }
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !receiving)) { context in
            Canvas { ctx, size in
                draw(in: &ctx, size: size, time: context.date.timeIntervalSinceReferenceDate)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.linear(duration: 0.09), value: level)
    }

    // Explicit CGFloat everywhere: mixing Double/CGFloat in long expressions made the
    // type checker time out in CI.

    private func draw(in ctx: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let outer: CGFloat = min(size.width, size.height) / 2
        let inner: CGFloat = outer * 0.80
        if mic == .onAir { drawGlow(in: &ctx, center: center, radius: inner * 0.86) }
        let head = sweepHead(time: time)
        let lit = Int(level * Float(Self.ticks))
        for i in 0..<Self.ticks {
            let on = isTickLit(i, head: head, lit: lit)
            drawTick(i, in: &ctx, center: center, inner: inner, outer: outer,
                     color: on ? activeColor : PelotonPalette.line)
        }
    }

    private func sweepHead(time: TimeInterval) -> Int {
        let turns: Double = time / 1.4
        return Int(turns * Double(Self.ticks)) % Self.ticks
    }

    private func isTickLit(_ i: Int, head: Int, lit: Int) -> Bool {
        if receiving { return (head - i + Self.ticks) % Self.ticks < Self.sweepLength }
        if mic == .paused || mic == .off { return false }
        return i < lit
    }

    private func drawGlow(in ctx: inout GraphicsContext, center: CGPoint, radius r: CGFloat) {
        let rect = CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)
        let alpha: Double = 0.12 + 0.25 * Double(level)
        ctx.fill(Path(ellipseIn: rect), with: .color(PelotonPalette.signal.opacity(alpha)))
    }

    private func drawTick(
        _ i: Int, in ctx: inout GraphicsContext,
        center: CGPoint, inner: CGFloat, outer: CGFloat, color: Color
    ) {
        let angle: CGFloat = CGFloat(i) / CGFloat(Self.ticks) * 2 * .pi - .pi / 2
        let dx: CGFloat = cos(angle)
        let dy: CGFloat = sin(angle)
        let major = i % 5 == 0
        let r0: CGFloat = major ? inner * 0.94 : inner
        let width: CGFloat = major ? outer * 0.035 : outer * 0.022
        var p = Path()
        p.move(to: CGPoint(x: center.x + dx * r0, y: center.y + dy * r0))
        p.addLine(to: CGPoint(x: center.x + dx * outer, y: center.y + dy * outer))
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
}
