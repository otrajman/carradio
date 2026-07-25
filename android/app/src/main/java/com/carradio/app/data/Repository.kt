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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /** PROTOCOL §4.2 — upload the opus burst. Returns the payload audio_path (with bucket prefix). */
    suspend fun uploadBurst(tripId: String, messageId: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val path = "$tripId/$messageId.ogg"
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

    /**
     * PROTOCOL §12 — synthetic-nodes edge function. Returns TTS scripts. The response shape is
     * parsed defensively: either a JSON array of objects with a "text" field, or an object
     * wrapping such an array under "messages"/"scripts".
     */
    suspend fun fetchSyntheticScripts(lat: Double, lng: Double): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = http.post(
                    "${Constants.SUPABASE_URL}/functions/v1/${Constants.SYNTHETIC_NODES_FN}"
                ) {
                    header("apikey", Constants.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer ${Constants.SUPABASE_ANON_KEY}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"lat":$lat,"lng":$lng}""")
                }
                if (!response.status.isSuccess()) return@withContext emptyList()
                parseSyntheticResponse(response.bodyAsText())
            } catch (e: Exception) {
                Log.w(TAG, "synthetic-nodes call failed", e)
                emptyList()
            }
        }

    private fun parseSyntheticResponse(body: String): List<String> {
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
                    el is JsonObject && el["text"] != null -> el["text"]!!.jsonPrimitive.content
                    el !is JsonObject && el !is JsonArray -> el.jsonPrimitive.content
                    else -> null
                }
            }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "CarRadioRepo"
    }
}
