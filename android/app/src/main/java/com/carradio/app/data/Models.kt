package com.carradio.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TripInsert(
    @SerialName("phonetic_handle") val phoneticHandle: String
)

@Serializable
data class TripRow(
    val id: String,
    @SerialName("phonetic_handle") val phoneticHandle: String,
    @SerialName("created_at") val createdAt: String = ""
)

/**
 * Breadcrumb insert (PROTOCOL §4.3). `location` is an EWKT string — Postgres casts
 * "SRID=4326;POINT(lng lat)" into geometry(Point,4326) on insert via PostgREST.
 */
@Serializable
data class MessageInsert(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    val kind: String,
    @SerialName("audio_path") val audioPath: String?,
    val text: String?,
    @SerialName("h3_r9") val h3R9: String,
    val location: String,
    val heading: Double,
    val speed: Double
)

@Serializable
data class MuteEventInsert(
    @SerialName("muter_trip_id") val muterTripId: String,
    @SerialName("muted_trip_id") val mutedTripId: String
)

/** Row shape returned by the get_breadcrumbs RPC (see supabase/migrations). */
@Serializable
data class BreadcrumbRow(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    val handle: String = "",
    val kind: String = "voice",
    @SerialName("audio_path") val audioPath: String? = null,
    val text: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val heading: Double = 0.0,
    val speed: Double = 0.0,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class ShadowbanParams(
    @SerialName("p_trip_id") val tripId: String
)

@Serializable
data class BreadcrumbParams(
    @SerialName("p_trip_id") val tripId: String,
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_radius_m") val radiusM: Double,
    @SerialName("p_since_hours") val sinceHours: Int = 24,
    @SerialName("p_limit") val limit: Int = 10
)
