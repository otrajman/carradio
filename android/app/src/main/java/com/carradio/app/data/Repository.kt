package com.carradio.app.data

import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.core.BurstPayload
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * All Supabase I/O (PostgREST inserts, RPCs, Storage upload, edge function calls).
 * Realtime channel management lives in service/RoomManager.
 */
class Repository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) {

    private val http = HttpClient(OkHttp)

    private var shadowbanCache: Pair<Long, Boolean>? = null

    suspend fun createTrip(handle: String): TripRow = withContext(Dispatchers.IO) {
        client.from("trips")
            .insert(TripInsert(handle)) { select() }
            .decodeSingle()
    }

    /**
     * PROTOCOL §4.1 — cached for 60 s. On network error assume NOT banned (fail-open:
     * a burst reaching a peer is better than silence caused by a flaky link).
     */
    suspend fun isShadowbanned(tripId: String): Boolean = withContext(Dispatchers.IO) {
        val cached = shadowbanCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < Constants.SHADOWBAN_CACHE_MS) {
            return@withContext cached.second
        }
        val banned = try {
            client.postgrest.rpc("is_shadowbanned", ShadowbanParams(tripId)).decodeAs<Boolean>()
        } catch (e: Exception) {
            Log.w(TAG, "is_shadowbanned failed", e)
            false
        }
        shadowbanCache = now to banned
        banned
    }

    /**
     * PROTOCOL §4.2 — upload the burst (Car Radio: opus `.ogg`; PelotonCB: AAC `.m4a`).
     * Returns the payload audio_path (with bucket prefix).
     */
    suspend fun uploadBurst(
        tripId: String,
        messageId: String,
        bytes: ByteArray,
        extension: String = "ogg"
    ): String =
        withContext(Dispatchers.IO) {
            val path = "$tripId/$messageId.$extension"
            client.storage.from(Constants.STORAGE_BUCKET).upload(path, bytes, upsert = false)
            "${Constants.STORAGE_BUCKET}/$path"
        }

    /** PROTOCOL §4.3 — breadcrumb row. */
    suspend fun insertMessage(payload: BurstPayload) = withContext(Dispatchers.IO) {
        client.from("messages").insert(
            MessageInsert(
                id = payload.messageId,
                tripId = payload.tripId,
                kind = payload.kind,
                audioPath = payload.audioPath,
                text = payload.text,
                h3R9 = payload.h3R9,
                location = "SRID=4326;POINT(${payload.lng} ${payload.lat})",
                heading = payload.heading.coerceIn(0.0, 359.999),
                speed = payload.speed.coerceIn(0.0, 149.0),
                convoyTag = payload.convoy
            )
        )
    }

    /** PROTOCOL §8 — stealth mute event. Best effort: local mute must win even if this fails. */
    suspend fun insertMuteEvent(muterTripId: String, mutedTripId: String) =
        withContext(Dispatchers.IO) {
            try {
                client.from("mute_events").insert(MuteEventInsert(muterTripId, mutedTripId))
            } catch (e: Exception) {
                Log.w(TAG, "mute_events insert failed", e)
            }
        }

    /** PROTOCOL §8 — report a burst/sender. Best effort, sender never notified. */
    suspend fun insertReport(
        reporterTripId: String,
        reportedTripId: String,
        messageId: String?,
        reason: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            client.from("reports").insert(
                ReportInsert(reporterTripId, reportedTripId, messageId, reason)
            )
        } catch (e: Exception) {
            Log.w(TAG, "reports insert failed", e)
        }
    }

    /** PROTOCOL §6 — breadcrumbs near a point, newest first, caller's trip excluded server-side. */
    suspend fun getBreadcrumbs(
        tripId: String,
        lat: Double,
        lng: Double,
        radiusM: Double,
        sinceHours: Int = 24,
        limit: Int = 10,
        convoyTag: String? = null
    ): List<BreadcrumbRow> = withContext(Dispatchers.IO) {
        try {
            client.postgrest.rpc(
                "get_breadcrumbs",
                BreadcrumbParams(tripId, lat, lng, radiusM, sinceHours, limit, convoyTag)
            ).decodeList()
        } catch (e: Exception) {
            Log.w(TAG, "get_breadcrumbs failed", e)
            emptyList()
        }
    }

    /** A server-generated system message: always has text; audio when Gemini voiced it. */
    data class SystemScript(val id: String?, val text: String, val audioPath: String?) {
        /** Local-only system burst for the play queue (never broadcast). */
        fun toPayload(lat: Double, lng: Double, handle: String, tripId: String): BurstPayload =
            BurstPayload(
                messageId = id ?: UUID.randomUUID().toString(),
                tripId = tripId,
                handle = handle,
                kind = BurstPayload.KIND_SYSTEM,
                audioPath = audioPath,
                text = text,
                lat = lat,
                lng = lng,
                createdAt = Instant.now().toString()
            )
    }

    /**
     * PROTOCOL §12 — synthetic-nodes edge function. Returns scripts with an optional
     * server-rendered voice (`audio_path` in the synthetic_voice bucket). The response shape
     * is parsed defensively: either a JSON array of objects with a "text" field, or an
     * object wrapping such an array under "messages"/"scripts".
     */
    suspend fun fetchSyntheticScripts(lat: Double, lng: Double): List<SystemScript> =
        withContext(Dispatchers.IO) {
            try {
                val body = postFunction(Constants.SYNTHETIC_NODES_FN, """{"lat":$lat,"lng":$lng}""")
                    ?: return@withContext emptyList()
                parseSyntheticResponse(body)
            } catch (e: Exception) {
                Log.w(TAG, "synthetic-nodes call failed", e)
                emptyList()
            }
        }

    /**
     * PROTOCOL §17 — ask the Road Guide about a burst this trip just sent. The server
     * transcribes it, runs the verification gate, and answers only route / scenery / POI
     * talk. Returns null (stay silent) for anything else, on any error, or when disabled.
     */
    suspend fun askRoadGuide(payload: BurstPayload): SystemScript? = withContext(Dispatchers.IO) {
        try {
            val request = buildJsonObject {
                put("trip_id", payload.tripId)
                put("message_id", payload.messageId)
                put("lat", payload.lat)
                put("lng", payload.lng)
                put("heading", payload.heading)
                put("speed", payload.speed)
            }
            val body = postFunction(Constants.ROAD_GUIDE_FN, request.toString())
                ?: return@withContext null
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return@withContext null
            if (root["respond"]?.jsonPrimitive?.content != "true") return@withContext null
            val text = root["text"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            SystemScript(
                id = root["id"]?.jsonPrimitive?.contentOrNull,
                text = text,
                audioPath = root["audio_path"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            Log.w(TAG, "road-guide call failed", e)
            null
        }
    }

    /** PROTOCOL §16.7 — keep the reserved DEMO pack populated while this trip is in it. */
    suspend fun pingDemoPack(tripId: String, lat: Double?, lng: Double?) = withContext(Dispatchers.IO) {
        try {
            val request = buildJsonObject {
                put("trip_id", tripId)
                if (lat != null) put("lat", lat)
                if (lng != null) put("lng", lng)
            }
            postFunction(Constants.DEMO_PACK_FN, request.toString())
        } catch (e: Exception) {
            Log.w(TAG, "demo-pack call failed", e)
            null
        }
    }

    private suspend fun postFunction(name: String, json: String): String? {
        val response = http.post("${Constants.SUPABASE_URL}/functions/v1/$name") {
            header("apikey", Constants.SUPABASE_ANON_KEY)
            header(HttpHeaders.Authorization, "Bearer ${Constants.SUPABASE_ANON_KEY}")
            contentType(ContentType.Application.Json)
            setBody(json)
        }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    private fun parseSyntheticResponse(body: String): List<SystemScript> {
        return try {
            val root = Json.parseToJsonElement(body)
            val array: JsonArray = when {
                root is JsonArray -> root
                root is JsonObject && root["messages"] is JsonArray -> root["messages"]!!.jsonArray
                root is JsonObject && root["scripts"] is JsonArray -> root["scripts"]!!.jsonArray
                else -> return emptyList()
            }
            array.mapNotNull { el ->
                when {
                    el is JsonObject && el["text"] != null -> SystemScript(
                        id = el["id"]?.jsonPrimitive?.contentOrNull,
                        text = el["text"]!!.jsonPrimitive.content,
                        audioPath = el["audio_path"]?.jsonPrimitive?.contentOrNull
                    )
                    el !is JsonObject && el !is JsonArray ->
                        SystemScript(null, el.jsonPrimitive.content, null)
                    else -> null
                }
            }.filter { it.text.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "CarRadioRepo"
    }
}
