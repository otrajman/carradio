// SupabaseService.swift
// Thin wrapper around supabase-swift (PostgREST, Storage, Functions, RPC).
// All backend I/O except realtime channels (see RealtimeCoordinator) goes
// through here so the SDK surface is centralized and easy to re-verify.

import Foundation
import Supabase

struct TripRow: Decodable {
    let id: UUID
}

final class SupabaseService {
    let client: SupabaseClient

    init() {
        client = SupabaseClient(
            supabaseURL: Constants.supabaseURL,
            supabaseKey: Constants.supabaseAnonKey
        )
    }

    // MARK: Trips (PROTOCOL §1)

    private struct TripInsert: Encodable {
        let phonetic_handle: String
    }

    /// INSERT INTO trips (phonetic_handle) ... RETURNING id
    func createTrip(handle: String) async throws -> UUID {
        let row: TripRow = try await client.from("trips")
            .insert(TripInsert(phonetic_handle: handle))
            .select("id")
            .single()
            .execute()
            .value
        return row.id
    }

    // MARK: Shadowban (PROTOCOL §4.1, §8)

    func isShadowbanned(tripID: UUID) async throws -> Bool {
        try await client
            .rpc("is_shadowbanned", params: ["p_trip_id": tripID.uuidString])
            .execute()
            .value
    }

    // MARK: Messages / breadcrumbs

    private struct MessageInsert: Encodable {
        let id: UUID
        let trip_id: UUID
        let kind: String
        let audio_path: String?
        let text: String?
        let h3_r9: String
        let location: String
        let heading: Double
        let speed: Double
    }

    /// PROTOCOL §4.3 — breadcrumb insert. `location` uses EWKT which PostgREST
    /// casts to PostGIS geometry (same form the deployed edge function uses).
    func insertMessage(
        id: UUID,
        tripID: UUID,
        kind: String,
        audioPath: String?,
        text: String?,
        h3R9: String,
        lat: Double,
        lng: Double,
        heading: Double,
        speed: Double
    ) async throws {
        try await client.from("messages")
            .insert(MessageInsert(
                id: id,
                trip_id: tripID,
                kind: kind,
                audio_path: audioPath,
                text: text,
                h3_r9: h3R9,
                location: "SRID=4326;POINT(\(lng) \(lat))",
                heading: heading,
                speed: speed
            ))
            .execute()
    }

    private struct BreadcrumbParams: Encodable {
        let p_trip_id: UUID
        let p_lat: Double
        let p_lng: Double
        let p_radius_m: Double
        let p_since_hours: Int
        let p_limit: Int
    }

    /// PROTOCOL §6 — signature verified against the deployed function:
    /// get_breadcrumbs(p_trip_id, p_lat, p_lng, p_radius_m, p_since_hours, p_limit).
    func fetchBreadcrumbs(
        tripID: UUID,
        lat: Double,
        lng: Double,
        radiusM: Double,
        sinceHours: Int = 24,
        limit: Int = 10
    ) async throws -> [BreadcrumbMessage] {
        try await client
            .rpc("get_breadcrumbs", params: BreadcrumbParams(
                p_trip_id: tripID,
                p_lat: lat,
                p_lng: lng,
                p_radius_m: radiusM,
                p_since_hours: sinceHours,
                p_limit: limit
            ))
            .execute()
            .value
    }

    // MARK: Mutes (PROTOCOL §8)

    private struct MuteEventInsert: Encodable {
        let muter_trip_id: UUID
        let muted_trip_id: UUID
    }

    func insertMuteEvent(muter: UUID, muted: UUID) async throws {
        try await client.from("mute_events")
            .insert(MuteEventInsert(muter_trip_id: muter, muted_trip_id: muted))
            .execute()
    }

    // MARK: Storage (PROTOCOL §4.2)

    /// Uploads a recorded burst. `path` is bucket-relative:
    /// "<trip_id>/<message_id>.m4a".
    func uploadVoiceBurst(path: String, data: Data) async throws {
        _ = try await client.storage
            .from(Constants.voiceBucket)
            .upload(path, data: data, options: FileOptions(contentType: "audio/mp4"))
    }

    // MARK: Synthetic nodes (PROTOCOL §12)

    private struct SyntheticBody: Encodable {
        let lat: Double
        let lng: Double
    }

    private struct SyntheticResponse: Decodable {
        let messages: [BreadcrumbMessage]
    }

    func fetchSyntheticNodes(lat: Double, lng: Double) async throws -> [BreadcrumbMessage] {
        let response: SyntheticResponse = try await client.functions.invoke(
            Constants.syntheticNodesFunction,
            options: FunctionInvokeOptions(body: SyntheticBody(lat: lat, lng: lng))
        )
        return response.messages
    }
}
