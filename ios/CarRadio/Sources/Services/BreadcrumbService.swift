// BreadcrumbService.swift
// Cold-start breadcrumbs per PROTOCOL §6 and synthetic nodes per §12:
//   - on every res-8 cell change: get_breadcrumbs RPC with
//     p_radius_m = max(1600, senderRadius(speed))
//   - on every res-7 cell change (once per cell per session): synthetic-nodes
//   - unplayed crumbs run through the shared filter (minus the age drop) and
//     drip out at most one per 45 s so live traffic wins
//   - played ids persist across restarts, capped at 2000 (PlayedIDStore)

import Foundation

@MainActor
final class BreadcrumbService {
    private let supabase: SupabaseService
    private let playback: PlaybackQueue
    private let playedIDs: PlayedIDStore
    private let filter: BurstFilter

    private var lastRes8Cell: String?
    private var syntheticCellsThisSession: Set<String> = []
    private var pending: [BreadcrumbMessage] = []
    private var lastBreadcrumbPlay: Date = .distantPast
    private var fetching = false

    var tripID: UUID?
    var mutedTripIDs: () -> Set<String> = { [] }
    var isElastic: () -> Bool = { false }

    init(supabase: SupabaseService, playback: PlaybackQueue, playedIDs: PlayedIDStore, filter: BurstFilter) {
        self.supabase = supabase
        self.playback = playback
        self.playedIDs = playedIDs
        self.filter = filter
    }

    /// Call on every location fix (2 s cadence).
    func onFix(_ state: GpsState) {
        guard let tripID else { return }

        if let res8 = RoomManager.res8Cell(lat: state.lat, lng: state.lng), res8 != lastRes8Cell {
            lastRes8Cell = res8
            fetchBreadcrumbs(tripID: tripID, state: state)
        }

        if Constants.syntheticNodes,
           let res7 = RoomManager.res7Cell(lat: state.lat, lng: state.lng),
           !syntheticCellsThisSession.contains(res7) {
            syntheticCellsThisSession.insert(res7)
            fetchSynthetic(state: state)
        }

        dripPending(state: state)
    }

    // MARK: Fetching

    private func fetchBreadcrumbs(tripID: UUID, state: GpsState) {
        guard !fetching else { return }
        fetching = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.fetching = false }
            do {
                let radius = max(1_600, GeoMath.senderRadiusMeters(speedMps: state.speed))
                let crumbs = try await self.supabase.fetchBreadcrumbs(
                    tripID: tripID,
                    lat: state.lat,
                    lng: state.lng,
                    radiusM: radius,
                    sinceHours: 24,
                    limit: 10
                )
                self.addPending(crumbs)
            } catch {
                NSLog("CarRadio breadcrumbs fetch failed: \(error)")
            }
        }
    }

    private func fetchSynthetic(state: GpsState) {
        Task { [weak self] in
            guard let self else { return }
            do {
                let messages = try await self.supabase.fetchSyntheticNodes(lat: state.lat, lng: state.lng)
                self.addPending(messages)
            } catch {
                NSLog("CarRadio synthetic-nodes failed: \(error)")
            }
        }
    }

    private func addPending(_ crumbs: [BreadcrumbMessage]) {
        for crumb in crumbs where !playedIDs.contains(crumb.id) && !pending.contains(where: { $0.id == crumb.id }) {
            pending.append(crumb)
        }
    }

    // MARK: Drip playback (≤ 1 per 45 s, live traffic wins)

    private func dripPending(state: GpsState) {
        guard !pending.isEmpty else { return }
        guard playback.isIdle else { return } // live bursts win
        guard Date().timeIntervalSince(lastBreadcrumbPlay) >= Constants.breadcrumbMinInterval else { return }
        guard let tripID else { return }

        while !pending.isEmpty {
            let crumb = pending.removeFirst()
            guard !playedIDs.contains(crumb.id) else { continue }

            // Same filter as §5, minus the age drop (§6). The RPC already
            // excludes our own trip, but the filter re-checks defensively.
            let result = filter.evaluate(
                payload: crumb.asPayload(),
                receiverTripID: tripID.uuidString.lowercased(),
                receiver: state,
                muted: mutedTripIDs(),
                elasticMode: isElastic(),
                isBreadcrumb: true
            )
            guard result.verdict == .play else {
                // Geometry said no — drop silently but don't mark played, the
                // crumb may match later from another position… unless muted.
                if result.verdict == .dropMuted || result.verdict == .dropDuplicate {
                    playedIDs.markPlayed(crumb.id)
                }
                continue
            }

            playedIDs.markPlayed(crumb.id)
            lastBreadcrumbPlay = Date()
            playback.enqueue(PlaybackQueue.Item(payload: crumb.asPayload(), isBreadcrumb: true))
            break // at most one per drip
        }
    }
}
