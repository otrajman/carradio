// Models.swift
// Wire-protocol models per docs/PROTOCOL.md. Pure Foundation — no UIKit.

import Foundation

/// The `burst` broadcast event payload (PROTOCOL §3).
public struct BurstPayload: Codable, Equatable, Sendable {
    public var v: Int
    public var messageID: String
    public var tripID: String
    public var handle: String
    /// "voice" | "system" (kept as String for forward compatibility).
    public var kind: String
    /// Path inside the public storage bucket, bucket-prefixed,
    /// e.g. "voice_bursts/<trip_id>/<message_id>.m4a". Nil for system/text bursts.
    public var audioPath: String?
    /// Non-nil for system bursts (client-side TTS).
    public var text: String?
    public var lat: Double
    public var lng: Double
    /// Degrees clockwise from true north, [0, 360).
    public var heading: Double
    /// Meters/second.
    public var speed: Double
    public var h3R9: String
    /// ISO-8601 UTC string.
    public var createdAt: String
    /// §14: present only in convoy mode (hashed invite code).
    public var convoy: String?

    public var isSystem: Bool { kind == "system" }

    enum CodingKeys: String, CodingKey {
        case v
        case messageID = "message_id"
        case tripID = "trip_id"
        case handle
        case kind
        case audioPath = "audio_path"
        case text
        case lat
        case lng
        case heading
        case speed
        case h3R9 = "h3_r9"
        case createdAt = "created_at"
        case convoy
    }

    public init(
        v: Int = 1,
        messageID: String,
        tripID: String,
        handle: String,
        kind: String,
        audioPath: String?,
        text: String?,
        lat: Double,
        lng: Double,
        heading: Double,
        speed: Double,
        h3R9: String,
        createdAt: String,
        convoy: String? = nil
    ) {
        self.v = v
        self.messageID = messageID
        self.tripID = tripID
        self.handle = handle
        self.kind = kind
        self.audioPath = audioPath
        self.text = text
        self.lat = lat
        self.lng = lng
        self.heading = heading
        self.speed = speed
        self.h3R9 = h3R9
        self.createdAt = createdAt
        self.convoy = convoy
    }
}

/// One row returned by the `get_breadcrumbs` RPC (verified against the deployed
/// function: get_breadcrumbs(p_trip_id uuid, p_lat, p_lng, p_radius_m=1600,
/// p_since_hours=24, p_limit=10) RETURNS TABLE(id, trip_id, handle, kind,
/// audio_path, text, lat, lng, heading, speed, created_at)).
public struct BreadcrumbMessage: Codable, Equatable, Sendable {
    public var id: String
    public var tripID: String
    public var handle: String
    public var kind: String
    public var audioPath: String?
    public var text: String?
    public var lat: Double
    public var lng: Double
    public var heading: Double
    public var speed: Double
    public var createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case tripID = "trip_id"
        case handle
        case kind
        case audioPath = "audio_path"
        case text
        case lat
        case lng
        case heading
        case speed
        case createdAt = "created_at"
    }

    public init(
        id: String, tripID: String, handle: String, kind: String,
        audioPath: String?, text: String?, lat: Double, lng: Double,
        heading: Double, speed: Double, createdAt: String
    ) {
        self.id = id
        self.tripID = tripID
        self.handle = handle
        self.kind = kind
        self.audioPath = audioPath
        self.text = text
        self.lat = lat
        self.lng = lng
        self.heading = heading
        self.speed = speed
        self.createdAt = createdAt
    }

    /// Adapts a breadcrumb into the shared burst shape so it can flow through
    /// the same receive filter and playback queue as live bursts (PROTOCOL §6).
    public func asPayload() -> BurstPayload {
        BurstPayload(
            messageID: id,
            tripID: tripID,
            handle: handle,
            kind: kind,
            audioPath: audioPath,
            text: text,
            lat: lat,
            lng: lng,
            heading: heading,
            speed: speed,
            h3R9: "",
            createdAt: createdAt
        )
    }
}

/// Receiver GPS state used by the filter and room math.
public struct GpsState: Equatable, Sendable {
    public var lat: Double
    public var lng: Double
    /// Degrees clockwise from true north, [0, 360).
    public var heading: Double
    /// Meters/second.
    public var speed: Double

    public init(lat: Double, lng: Double, heading: Double, speed: Double) {
        self.lat = lat
        self.lng = lng
        self.heading = heading
        self.speed = speed
    }
}

/// ISO-8601 helpers shared by the wire layer.
public enum WireDate {
    /// Formatter matching the protocol example: "2026-07-23T05:12:00.000Z".
    public static func string(from date: Date) -> String {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fmt.string(from: date)
    }

    public static func date(from string: String) -> Date? {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = fmt.date(from: string) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: string)
    }
}
