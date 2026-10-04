// JoinView.swift
// Pre-ride: join a pack by code, mint + share a new code, or ride open road.

import SwiftUI

struct JoinView: View {
    @EnvironmentObject private var model: PelotonModel
    @AppStorage("last_pack_code") private var code = ""
    @AppStorage("rider_name") private var name = ""
    @State private var sharing: String?
    @AppStorage(Constants.roadGuideDefaultsKey) private var roadGuide = true

    private var valid: Bool { PelotonTag.fromCode(code) != nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text("PELOTON")
                        .font(.system(size: 44, weight: .black))
                        .kerning(-1)
                        .foregroundStyle(PelotonPalette.ink)
                    Text("CB")
                        .font(.system(size: 30, weight: .black))
                        .foregroundStyle(PelotonPalette.onSignal)
                        .padding(.horizontal, 10)
                        .background(PelotonPalette.signal, in: RoundedRectangle(cornerRadius: 8))
                }
                Text("Hands-free radio for your group ride. Talk and the pack hears you.")
                    .font(.system(size: 16))
                    .foregroundStyle(PelotonPalette.muted)
                    .padding(.top, 6)

                Eyebrow(text: "Your name · optional").padding(.top, 28)
                TextField(
                    "",
                    text: $name,
                    prompt: Text("Leave blank for a random one").foregroundColor(PelotonPalette.muted)
                )
                .font(.system(size: 20, weight: .bold))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onChange(of: name) { newValue in
                    let clipped = String(newValue.prefix(RiderName.maxLength))
                    if clipped != newValue { name = clipped }
                }
                .foregroundStyle(PelotonPalette.ink)
                .padding(.vertical, 16)
                .background(PelotonPalette.surface, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(PelotonPalette.line, lineWidth: 2))
                .padding(.top, 8)

                Eyebrow(text: "Join a pack").padding(.top, 24)
                TextField("", text: $code, prompt: Text("PACK CODE").foregroundColor(PelotonPalette.line))
                    .font(.system(size: 34, weight: .black, design: .monospaced))
                    .kerning(3)
                    .multilineTextAlignment(.center)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { if valid { join() } }
                    .onChange(of: code) { newValue in
                        let upper = String(newValue.uppercased().prefix(24))
                        if upper != newValue { code = upper }
                    }
                    .foregroundStyle(PelotonPalette.ink)
                    .padding(.vertical, 18)
                    .background(PelotonPalette.surface, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(valid ? PelotonPalette.ink : PelotonPalette.line, lineWidth: 2)
                    )
                    .padding(.top, 8)

                HStack(spacing: 12) {
                    BigButton(
                        label: "JOIN PACK",
                        background: valid ? PelotonPalette.signal : PelotonPalette.line,
                        foreground: valid ? PelotonPalette.onSignal : PelotonPalette.muted,
                        enabled: valid,
                        action: join
                    )
                    .layoutPriority(1.4)
                    BigButton(
                        label: "NEW CODE",
                        background: PelotonPalette.surface,
                        foreground: PelotonPalette.ink,
                        border: PelotonPalette.ink
                    ) {
                        code = PelotonTag.generateCode()
                        sharing = code
                    }
                }
                .padding(.top, 12)
                Text("Everyone who enters the same code hears each other — anywhere, any distance.")
                    .font(.system(size: 13))
                    .foregroundStyle(PelotonPalette.muted)
                    .padding(.top, 10)

                HStack {
                    Rectangle().fill(PelotonPalette.line).frame(height: 2)
                    Text("OR").font(.system(size: 13, weight: .bold)).foregroundStyle(PelotonPalette.muted)
                    Rectangle().fill(PelotonPalette.line).frame(height: 2)
                }
                .padding(.vertical, 28)

                Button { model.startRide(code: nil, name: name) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("RIDE OPEN ROAD")
                            .font(.system(size: 26, weight: .black))
                            .kerning(1)
                        Text("No code. Hear any PelotonCB rider within 500 m heading your way.")
                            .font(.system(size: 14))
                            .opacity(0.7)
                            .multilineTextAlignment(.leading)
                    }
                    .foregroundStyle(PelotonPalette.background)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(22)
                    .background(PelotonPalette.ink, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)

                Toggle(isOn: $roadGuide) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ROAD GUIDE AI")
                            .font(.system(size: 16, weight: .black))
                            .foregroundStyle(PelotonPalette.ink)
                        Text("Ask about the route, the view, or places nearby — it answers only you, and ignores everything else.")
                            .font(.system(size: 13))
                            .foregroundStyle(PelotonPalette.muted)
                    }
                }
                .tint(PelotonPalette.pack)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(PelotonPalette.line, lineWidth: 2))
                .padding(.top, 20)

                Text("Voice kept 24 h · no accounts")
                    .font(.system(size: 12))
                    .foregroundStyle(PelotonPalette.muted)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 36)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .sheet(item: Binding(
            get: { sharing.map(ShareCode.init) },
            set: { sharing = $0?.code }
        )) { item in
            ShareSheet(text: "Ride with us on PelotonCB — pack code \(item.code)")
        }
    }

    private func join() {
        model.startRide(code: code, name: name)
    }
}

struct ShareCode: Identifiable {
    let code: String
    var id: String { code }
}

struct ShareSheet: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
