// CarPlaySceneDelegate.swift
// CarPlay radar dashboard: a dark status template (no maps, ever) with the
// handle, peer count and last speaker, plus Talk and Skip+Mute actions.
// Requires the com.apple.developer.carplay-communication entitlement, which
// needs Apple approval — see README.md.

import Foundation
import UIKit
import CarPlay
import Combine

final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var template: CPInformationTemplate?
    private var cancellables: Set<AnyCancellable> = []

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController

        let template = makeTemplate()
        self.template = template
        interfaceController.setRootTemplate(template, animated: true) { _, _ in }

        // Rebuild the template whenever app state changes (throttled).
        let model = AppModel.shared
        model.objectWillChange
            .throttle(for: .seconds(1), scheduler: RunLoop.main, latest: true)
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.refresh()
                }
            }
            .store(in: &cancellables)

        // A trip starts automatically when the car connects and none is live.
        DispatchQueue.main.async {
            if model.phase == .idle {
                model.startTrip()
            }
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        cancellables.removeAll()
        self.interfaceController = nil
        self.template = nil
    }

    // MARK: Template

    private func makeTemplate() -> CPInformationTemplate {
        CPInformationTemplate(
            title: "Car Radio",
            layout: .leading,
            items: informationItems(),
            actions: actionButtons()
        )
    }

    private func refresh() {
        template?.items = informationItems()
        template?.actions = actionButtons()
    }

    private func informationItems() -> [CPInformationItem] {
        let model = AppModel.shared
        let status: String
        if model.isRecording {
            status = "● ON AIR — recording"
        } else if model.isPlayingBurst {
            status = "▶ Receiving"
        } else if model.isElasticMode {
            status = "Wide range — quiet road"
        } else if model.phase == .live {
            status = "Listening"
        } else {
            status = "Off air"
        }
        return [
            CPInformationItem(title: "Status", detail: status),
            CPInformationItem(title: "Handle", detail: model.handle.isEmpty ? "—" : model.handle),
            CPInformationItem(title: "Drivers nearby", detail: "\(model.peerCount)"),
            CPInformationItem(title: "Last heard", detail: model.lastSpeakerHandle ?? "—"),
        ]
    }

    private func actionButtons() -> [CPTextButton] {
        let model = AppModel.shared
        let talkTitle = model.isRecording ? "Send" : "Talk"
        return [
            CPTextButton(title: talkTitle, textStyle: .confirm) { _ in
                DispatchQueue.main.async {
                    AppModel.shared.toggleTalk()
                }
            },
            CPTextButton(title: "Skip + Mute", textStyle: .cancel) { _ in
                DispatchQueue.main.async {
                    AppModel.shared.skipAndMute()
                }
            },
        ]
    }
}
