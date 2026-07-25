// RootView.swift
// Phase switch: start screen → drive mode. Dark only.

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch model.phase {
            case .idle:
                StartView()
            case .starting:
                VStack(spacing: 16) {
                    ProgressView()
                        .tint(.green)
                    Text("Tuning in…")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
            case .live:
                DriveModeView()
            case .failed(let message):
                VStack(spacing: 20) {
                    Image(systemName: "antenna.radiowaves.left.and.right.slash")
                        .font(.system(size: 44))
                        .foregroundStyle(.red)
                    Text(message)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Try again") {
                        model.startTrip()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                }
                .padding(32)
            }
        }
        .statusBarHidden(model.phase == .live)
    }
}

private struct StartView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("convoy_code") private var convoyCode = ""

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(.green)
            Text("CAR RADIO")
                .font(.system(size: 34, weight: .black, design: .rounded))
                .kerning(4)
            Text("Talk to the traffic around you.\nNew handle every trip. Nothing is kept.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Spacer()
            TextField("Convoy code (optional — friends only)", text: $convoyCode)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 40)
                .onChange(of: convoyCode) { newValue in
                    model.setConvoyCode(newValue)
                }
            Button {
                model.startTrip()
            } label: {
                Text("GO ON AIR")
                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .foregroundStyle(.black)
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
    }
}
