// BurstFilter.swift
// The receive filter, implemented exactly per docs/PROTOCOL.md §5 (steps 1-5).
// Step 6 (FIFO queue + age drop at dequeue) lives in the playback queue.
// Pure Foundation — no UIKit.

import Foundation

/// Order-preserving LRU set for message-id dedupe (PROTOCOL §5.2, ~200 ids).
public final class SeenLRU {
    private var order: [String] = []
    private var set: Set<String> = []
    private let capacity: Int

    public init(capacity: Int = 200) {
        self.capacity = max(1, capacity)
    }

    public func contains(_ id: String) -> Bool { set.contains(id) }

    public func insert(_ id: String) {
        guard !set.contains(id) else { return }
        order.append(id)
        set.insert(id)
        while order.count > capacity {
            set.remove(order.removeFirst())
        }
    }

    public var count: Int { order.count }
}

public enum FilterVerdict: Equatable, Sendable {
    case play
    case dropSelf
    case dropDuplicate
    case dropMuted
    case dropHeading
    case dropDistance
}

public struct FilterResult: Equatable, Sendable {
    /// The effective verdict given the current mode (elastic or strict).
    public let verdict: FilterVerdict
    /// Whether the burst would pass the *strict* (non-elastic) filter.
    /// Used to exit elastic mode (PROTOCOL §7).
    public let passesStrict: Bool
}

/// Stateful receive filter: owns the dedupe LRU; everything else is pure math.
public final class BurstFilter {
    /// Heading-alignment threshold, dot ≥ 0.85 (±31.8°).
    public static let headingDotThreshold = 0.85
    /// Ahead-of-sender cone threshold, cos ≥ 0.5 (±60°).
    public static let aheadDotThreshold = 0.5
    /// Omnidirectional near-bubble radius, meters.
    public static let nearBubbleMeters = 800.0
    /// Below this receiver speed the heading check is skipped (parked / jam creep).
    public static let headingCheckMinSpeedMps = 3.0

    private let seen: SeenLRU

    public init(seen: SeenLRU = SeenLRU(capacity: 200)) {
        self.seen = seen
    }

    /// Runs PROTOCOL §5 steps 1-5 on an incoming burst.
    ///
    /// - Parameters:
    ///   - payload: sender fields S from the wire.
    ///   - receiverTripID: our current trip id.
    ///   - receiver: our current GPS state R.
    ///   - muted: local stealth-mute set of trip ids.
    ///   - elasticMode: PROTOCOL §7 — when true, the heading check (§5.4) and
    ///     the distance/forward-cone check (§5.5) are skipped entirely so
    ///     anything in the subscribed rooms (~2.4 km) plays. §7 says "skip the
    ///     heading check and treat senderRadius as ∞ within subscribed rooms";
    ///     we read that as regional mode with no directional gating (see
    ///     HANDOFF.md — cross-client note).
    ///   - isBreadcrumb: breadcrumbs skip self/dedupe bookkeeping differences:
    ///     they still dedupe (played-ids are handled separately by the caller)
    ///     and still run the same geometry checks (PROTOCOL §6).
    public func evaluate(
        payload: BurstPayload,
        receiverTripID: String,
        receiver: GpsState,
        muted: Set<String>,
        elasticMode: Bool,
        isBreadcrumb: Bool = false
    ) -> FilterResult {
        // 1. Self
        if payload.tripID == receiverTripID {
            return FilterResult(verdict: .dropSelf, passesStrict: false)
        }

        // 2. Dedupe (senders fan out to multiple rooms)
        if seen.contains(payload.messageID) {
            return FilterResult(verdict: .dropDuplicate, passesStrict: false)
        }
        seen.insert(payload.messageID)

        // 3. Mute
        if muted.contains(payload.tripID) {
            return FilterResult(verdict: .dropMuted, passesStrict: false)
        }

        // 4 + 5. Geometry. Elastic mode plays anything in the subscribed rooms,
        // but we still compute the strict verdict so the caller can exit
        // elastic mode when a burst passes the strict filter (§7).
        let strict = geometryVerdict(payload: payload, receiver: receiver)
        let effective = elasticMode ? FilterVerdict.play : strict
        return FilterResult(verdict: effective, passesStrict: strict == .play)
    }

    /// PROTOCOL §5.4 (heading match) + §5.5 (distance / forward cone), strict.
    private func geometryVerdict(payload: BurstPayload, receiver: GpsState) -> FilterVerdict {
        // 4. Heading match — skipped when receiver is slow (parked/jam creep)
        //    or for system bursts.
        let skipHeading = receiver.speed < Self.headingCheckMinSpeedMps
            || payload.isSystem
        if !skipHeading {
            let dot = GeoMath.headingDot(payload.heading, receiver.heading)
            if dot < Self.headingDotThreshold {
                return .dropHeading
            }
        }

        // 5. Distance / forward cone (asymmetric, sender-owned).
        let d = GeoMath.haversineMeters(
            lat1: payload.lat, lng1: payload.lng,
            lat2: receiver.lat, lng2: receiver.lng
        )
        if d <= Self.nearBubbleMeters {
            return .play
        }
        // Receiver must be ahead of the sender.
        let bearing = GeoMath.bearingDegrees(
            lat1: payload.lat, lng1: payload.lng,
            lat2: receiver.lat, lng2: receiver.lng
        )
        let aheadDot = cos((bearing - payload.heading) * .pi / 180)
        guard aheadDot >= Self.aheadDotThreshold else {
            return .dropDistance
        }
        guard d <= GeoMath.senderRadiusMeters(speedMps: payload.speed) else {
            return .dropDistance
        }
        return .play
    }
}
