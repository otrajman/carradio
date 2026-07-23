// CarRadioApp.swift
// SwiftUI app entry. The CarPlay scene is declared in Info.plist
// (UIApplicationSceneManifest → CPTemplateApplicationSceneSessionRoleApplication
// → CarPlaySceneDelegate); SwiftUI keeps managing the phone scene.

import SwiftUI

@main
struct CarRadioApp: App {
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark) // dark only (PROTOCOL §13)
        }
    }
}
