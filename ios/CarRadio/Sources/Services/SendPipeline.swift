// SendPipeline.swift
// The send pipeline, in the exact order of PROTOCOL §4:
//   1. shadowban check first (cached 60 s) — if banned, PRETEND to send
//   2. upload audio to storage:  <trip_id>/<message_id>.m4a
//   3. INSERT INTO messages (breadcrumb)
//   4. broadcast `burst` to every room in the publish set

import Foundation

@MainActor
final class SendPipeline {
    private let supabase: SupabaseService
    private let realtime: RealtimeCoordinator
    private let earcons: EarconPlayer

    private var shadowbanCache: (value: Bool, at: Date)?

    init(supabase: SupabaseService, realtime: RealtimeCoordinator, earcons: EarconPlayer) {
        self.supabase = supabase
        self.realtime = realtime
        self.earcons = earcons
    }

    /// Sends a recorded burst. Never throws — failures are logged and the
    /// caller's UX proceeds. Returns the payload that actually went out, or nil
    /// (shadowbanned pretend-send or error).
    @discardableResult
    func sendBurst(
        fileURL: URL,
        tripID: UUID,
        handle: String,
        state: GpsState,
        convoyTag: String? = nil,
        publishRooms overrideRooms: [String]? = nil,
        playSentCue: Bool = true,
        deliverFirst: Bool = false
    ) async -> BurstPayload? {
        // 1. Shadowban check FIRST. On RPC failure, assume not banned.
        if await isShadowbanned(tripID: tripID) {
            // Pretend to send: play the sent earcon, skip steps 2-4 (§4.1, §8).
            guard playSentCue else { return nil }
            AudioSessionController.shared.beginPlayback()
            await earcons.play(.sent)
            AudioSessionController.shared.end()
            return nil
        }

        guard let h3r9 = RoomManager.res9Cell(lat: state.lat, lng: state.lng) else {
            return nil
        }
        let messageID = UUID()
        let bucketPath = "\(tripID.uuidString.lowercased())/\(messageID.uuidString.lowercased()).m4a"
        let audioPath = "\(Constants.voiceBucket)/\(bucketPath)"

        do {
            let data = try Data(contentsOf: fileURL)

            // 2. Upload to storage.
            try await supabase.uploadVoiceBurst(path: bucketPath, data: data)

            let payload = BurstPayload(
                messageID: messageID.uuidString.lowercased(),
                tripID: tripID.uuidString.lowercased(),
                handle: handle,
                kind: "voice",
                audioPath: audioPath,
                text: nil,
                lat: state.lat,
                lng: state.lng,
                heading: state.heading,
                speed: state.speed,
                h3R9: h3r9,
                createdAt: WireDate.string(from: Date()),
                convoy: convoyTag
            )
            // PelotonCB passes its pack/geo channel; Car Radio uses the §3 forward fan-out.
            let rooms = overrideRooms ?? RoomManager.publishRooms(
                lat: state.lat, lng: state.lng,
                heading: state.heading, speed: state.speed
            )

            // PelotonCB (§16.4): the pack hears it as soon as the audio is up; the row
            // follows and never delays delivery.
            if deliverFirst { await realtime.broadcast(payload, to: rooms) }

            // 3. Insert the breadcrumb row.
            do {
                try await supabase.insertMessage(
                    id: messageID,
                    tripID: tripID,
                    kind: "voice",
                    audioPath: audioPath,
                    text: nil,
                    h3R9: h3r9,
                    lat: state.lat,
                    lng: state.lng,
                    heading: state.heading,
                    speed: state.speed,
                    convoyTag: convoyTag
                )
            } catch {
                guard deliverFirst else { throw error }
                NSLog("CarRadio message row insert failed: \(error)")
            }

            // 4. Broadcast to the publish set.
            if !deliverFirst { await realtime.broadcast(payload, to: rooms) }

            guard playSentCue else { return payload }
            AudioSessionController.shared.beginPlayback()
            await earcons.play(.sent)
            AudioSessionController.shared.end()
            return payload
        } catch {
            NSLog("CarRadio send failed: \(error)")
            return nil
        }
    }

    private func isShadowbanned(tripID: UUID) async -> Bool {
        if let cached = shadowbanCache,
           Date().timeIntervalSince(cached.at) < Constants.shadowbanCacheSeconds {
            return cached.value
        }
        let banned = (try? await supabase.isShadowbanned(tripID: tripID)) ?? false
        shadowbanCache = (banned, Date())
        return banned
    }
}
