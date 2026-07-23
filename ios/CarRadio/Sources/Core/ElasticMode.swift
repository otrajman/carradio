// ElasticMode.swift
// Density fallback state machine per docs/PROTOCOL.md §7:
//   - Enter after 3 continuous minutes of (0 other non-system presence members
//     AND no bursts passing the strict filter).
//   - Exit immediately when a burst passes the strict filter or presence shows
//     ≥ 2 peers.
// Pure Foundation — no UIKit. Time is injected for testability.

import Foundation

public final class ElasticMode {
    /// Silence window before entering elastic mode.
    public static let enterAfterSeconds: TimeInterval = 180
    /// Peer count that exits elastic mode immediately.
    public static let exitPeerCount = 2

    public private(set) var isElastic = false

    /// Start of the current continuous silence window (nil = not silent).
    private var silenceStart: Date?
    private var lastPeerCount = 0

    public init() {}

    /// Call whenever presence changes: `peerCount` is the number of *other*,
    /// non-system members across subscribed rooms.
    public func updatePresence(peerCount: Int, now: Date = Date()) {
        lastPeerCount = peerCount
        if peerCount >= Self.exitPeerCount {
            isElastic = false
            silenceStart = nil
            return
        }
        if peerCount > 0 {
            // Someone is around; the silence clock only runs at 0 peers.
            silenceStart = nil
            return
        }
        tick(now: now)
    }

    /// Call when a received burst passed the STRICT filter (PROTOCOL §7 exit).
    public func noteStrictFilterPass(now: Date = Date()) {
        isElastic = false
        silenceStart = nil
    }

    /// Call periodically (and on presence updates) to advance the silence clock.
    public func tick(now: Date = Date()) {
        guard !isElastic else { return }
        guard lastPeerCount == 0 else { return }
        if let start = silenceStart {
            if now.timeIntervalSince(start) >= Self.enterAfterSeconds {
                isElastic = true
            }
        } else {
            silenceStart = now
        }
    }
}
