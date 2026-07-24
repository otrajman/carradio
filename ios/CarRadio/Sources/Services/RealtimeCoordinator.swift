// RealtimeCoordinator.swift
// Supabase Realtime V2 channel management per PROTOCOL §3:
//   - subscribe set  = gridDisk(res7, 1) rooms (recomputed on cell change)
//   - presence       = tracked on OWN cell's channel only, throttled 10 s
//   - publish        = broadcast "burst" to the publish set rooms
// Broadcast channels are public: broadcast { self: false, ack: false }.

import Foundation
import Supabase

/// Presence payload (PROTOCOL §3): { trip_id, handle, heading, speed, kind }.
struct PresencePayload: Codable {
    var trip_id: String
    var handle: String
    var heading: Double
    var speed: Double
    var kind: String // "human" for this client, "system" for bots
}

@MainActor
final class RealtimeCoordinator {
    private let client: SupabaseClient

    /// Fired for every incoming `burst` event across subscribed rooms.
    var onBurst: ((BurstPayload) -> Void)?
    /// Fired when the observed peer count changes (other, non-system members
    /// across all subscribed rooms).
    var onPeerCountChange: ((Int) -> Void)?

    private var channels: [String: RealtimeChannelV2] = [:]
    private var listenTasks: [String: [Task<Void, Never>]] = [:]
    /// room -> set of member trip_ids (from presence joins/leaves).
    private var presenceMembers: [String: Set<String>] = [:]

    private var subscribedRooms: Set<String> = []
    private var ownRoom: String?
    private var myTripID: String = ""
    private var lastPresenceTrack: Date = .distantPast

    init(client: SupabaseClient) {
        self.client = client
    }

    // MARK: Room lifecycle

    /// Reconciles the channel pool with the desired subscribe set and tracks
    /// presence on the (possibly new) own-cell room.
    func updateRooms(subscribe: Set<String>, ownRoom newOwnRoom: String, presence: PresencePayload) async {
        myTripID = presence.trip_id

        // Tear down channels that left the set.
        for room in subscribedRooms.subtracting(subscribe) {
            await removeRoom(room)
        }

        // Bring up channels that joined the set.
        for room in subscribe.subtracting(subscribedRooms) {
            await ensureChannel(room)
        }
        subscribedRooms = subscribe

        // Presence follows the own-cell channel.
        if ownRoom != newOwnRoom {
            if let previous = ownRoom, let channel = channels[previous] {
                await channel.untrack()
            }
            ownRoom = newOwnRoom
            lastPresenceTrack = .distantPast
        }
        await trackPresence(presence)
        recomputePeerCount()
    }

    /// Tracks presence on the own-cell channel, throttled to one update / 10 s.
    func trackPresence(_ presence: PresencePayload) async {
        guard let room = ownRoom, let channel = channels[room] else { return }
        guard Date().timeIntervalSince(lastPresenceTrack) >= Constants.presenceThrottle else { return }
        lastPresenceTrack = Date()
        do {
            try await channel.track(presence)
        } catch {
            NSLog("CarRadio realtime: presence track failed: \(error)")
        }
    }

    /// Broadcasts a burst to every room in the publish set (PROTOCOL §4.4).
    /// Rooms outside the subscribe set get a short-lived channel.
    func broadcast(_ payload: BurstPayload, to rooms: [String]) async {
        for room in rooms {
            let channel: RealtimeChannelV2
            var ephemeral = false
            if let existing = channels[room] {
                channel = existing
            } else {
                channel = makeChannel(room)
                await channel.subscribe()
                ephemeral = true
            }
            do {
                try await channel.broadcast(event: "burst", message: payload)
            } catch {
                NSLog("CarRadio realtime: broadcast to \(room) failed: \(error)")
            }
            if ephemeral {
                await client.removeChannel(channel)
            }
        }
    }

    func shutdown() async {
        for room in Array(channels.keys) {
            await removeRoom(room)
        }
        subscribedRooms = []
        ownRoom = nil
    }

    // MARK: Internals

    private func makeChannel(_ room: String) -> RealtimeChannelV2 {
        client.channel(room) { config in
            config.broadcast.receiveOwnBroadcasts = false
            config.broadcast.acknowledgeBroadcasts = false
        }
    }

    private func ensureChannel(_ room: String) async {
        guard channels[room] == nil else { return }
        let channel = makeChannel(room)
        channels[room] = channel

        let burstStream = channel.broadcastStream(event: "burst")
        let presenceStream = channel.presenceChange()

        let burstTask = Task { [weak self] in
            for await message in burstStream {
                guard !Task.isCancelled else { break }
                self?.handleBroadcast(message)
            }
        }
        let presenceTask = Task { [weak self] in
            for await action in presenceStream {
                guard !Task.isCancelled else { break }
                self?.handlePresence(action: action, room: room)
            }
        }
        listenTasks[room] = [burstTask, presenceTask]

        await channel.subscribe()
    }

    private func removeRoom(_ room: String) async {
        listenTasks[room]?.forEach { $0.cancel() }
        listenTasks[room] = nil
        presenceMembers[room] = nil
        if let channel = channels.removeValue(forKey: room) {
            await client.removeChannel(channel)
        }
        recomputePeerCount()
    }

    private func handleBroadcast(_ message: JSONObject) {
        // broadcastStream yields the whole realtime message; the burst payload
        // sits under "payload". Fall back to the message itself defensively.
        let payloadObject = message["payload"]?.objectValue ?? message
        do {
            let data = try JSONEncoder().encode(AnyJSON.object(payloadObject))
            let payload = try JSONDecoder().decode(BurstPayload.self, from: data)
            onBurst?(payload)
        } catch {
            NSLog("CarRadio realtime: undecodable burst: \(error)")
        }
    }

    private func handlePresence(action: any PresenceAction, room: String) {
        var members = presenceMembers[room] ?? []
        // joins/leaves are keyed by presence ref: [String: PresenceV2]
        for presence in action.joins.values {
            if let info = decodePresence(presence.state) {
                // Only count human peers that aren't us (PROTOCOL §7).
                if info.kind != "system", info.trip_id != myTripID {
                    members.insert(info.trip_id)
                }
            }
        }
        for presence in action.leaves.values {
            if let info = decodePresence(presence.state) {
                members.remove(info.trip_id)
            }
        }
        presenceMembers[room] = members
        recomputePeerCount()
    }

    private func decodePresence(_ state: JSONObject) -> PresencePayload? {
        guard let data = try? JSONEncoder().encode(AnyJSON.object(state)) else { return nil }
        return try? JSONDecoder().decode(PresencePayload.self, from: data)
    }

    private func recomputePeerCount() {
        let all = presenceMembers.values.reduce(into: Set<String>()) { $0.formUnion($1) }
        onPeerCountChange?(all.count)
    }
}
