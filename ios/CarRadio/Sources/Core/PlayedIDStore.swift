// PlayedIDStore.swift
// Persistent set of played breadcrumb message ids (PROTOCOL §6): survives app
// restart, capped at 2000 (oldest evicted first). Backed by UserDefaults.
// Pure Foundation — no UIKit.

import Foundation

public final class PlayedIDStore {
    public static let cap = 2_000
    private static let defaultsKey = "carradio.playedBreadcrumbIDs"

    private let defaults: UserDefaults
    private var order: [String]
    private var set: Set<String>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.stringArray(forKey: Self.defaultsKey) ?? []
        self.order = stored
        self.set = Set(stored)
    }

    public func contains(_ id: String) -> Bool { set.contains(id) }

    public var count: Int { order.count }

    public func markPlayed(_ id: String) {
        guard !set.contains(id) else { return }
        order.append(id)
        set.insert(id)
        while order.count > Self.cap {
            set.remove(order.removeFirst())
        }
        defaults.set(order, forKey: Self.defaultsKey)
    }
}
