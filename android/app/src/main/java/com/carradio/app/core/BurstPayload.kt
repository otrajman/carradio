package com.carradio.app.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The realtime `burst` broadcast event payload, PROTOCOL §3. Wire units:
 * speed m/s, heading degrees [0,360), WGS84 decimal degrees, ISO-8601 UTC timestamps.
 */
@Serializable
data class BurstPayload(
    val v: Int = 1,
    @SerialName("message_id") val messageId: String,
    @SerialName("trip_id") val tripId: String,
    val handle: String = "",
    val kind: String = KIND_VOICE,
    /** Path inside the public storage bucket incl. bucket prefix, e.g. "voice_bursts/<trip>/<msg>.ogg". Null for text/system bursts. */
    @SerialName("audio_path") val audioPath: String? = null,
    /** Non-null for system bursts (client-side TTS) or bot bursts. */
    val text: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val heading: Double = 0.0,
    val speed: Double = 0.0,
    @SerialName("h3_r9") val h3R9: String = "",
    @SerialName("created_at") val createdAt: String = ""
) {
    val isSystem: Boolean get() = kind == KIND_SYSTEM

    companion object {
        const val KIND_VOICE = "voice"
        const val KIND_SYSTEM = "system"

        @OptIn(ExperimentalSerializationApi::class)
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true // wire payloads carry explicit "audio_path": null / "text": null
        }

        fun decodeOrNull(raw: String): BurstPayload? = try {
            json.decodeFromString(serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}
