// PelotonApp.swift
// PelotonCB entry point: phone only (no CarPlay scene), follows the system appearance.

import SwiftUI

@main
struct PelotonApp: App {
    @StateObject private var model = PelotonModel.shared

    var body: some Scene {
        WindowGroup {
            PelotonRootView()
                .environmentObject(model)
        }
    }
}

struct PelotonRootView: View {
    @EnvironmentObject private var model: PelotonModel

    var body: some View {
        ZStack {
            PelotonPalette.background.ignoresSafeArea()
            switch model.phase {
            case .join:
                JoinView()
            case .starting:
                VStack(spacing: 16) {
                    ProgressView().tint(PelotonPalette.signal)
                    Text("Finding your pack…")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(PelotonPalette.muted)
                }
            case .riding:
                RideView()
            case .failed(let message):
                VStack(spacing: 20) {
                    Text(message)
                        .font(.system(size: 18, weight: .bold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(PelotonPalette.ink)
                    BigButton(label: "BACK", background: PelotonPalette.signal,
                              foreground: PelotonPalette.onSignal) {
                        model.leaveRide()
                    }
                }
                .padding(32)
            }
        }
    }
}
