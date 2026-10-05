package com.carradio.app.peloton

import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.core.BurstPayload
import com.carradio.app.core.PelotonTag
import com.carradio.app.service.H3Provider
import com.carradio.app.service.LocationEngine
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PresenceAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * PROTOCOL §16 realtime channels for a ride.
 *
 * - Pack mode (join by code): ONE channel `peloton:pack:<tag>` wherever the riders are —
 *   a dropped rider 5 km back still hears the pack. Presence on that channel = the roster.
 * - Open-road mode: `peloton:geo:<h3_res8>` rooms, subscribe gridDisk(own, 1), publish to
 *   the own cell only (res-8 disks cover the 500 m pack radius from anywhere in the cell).
 *   Presence on the own cell only, like Car Radio §3.
 */
class PelotonChannels(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
    private val packTag: String?,
    private val selfTripId: () -> String?,
    private val selfHandle: () -> String,
    private val onBurst: (BurstPayload) -> Unit,
    private val onRiderCountChanged: (Int) -> Unit,
    /** §16.7: synthetic riders the server seats in the DEMO pack (not in presence). */
    private val extraRiders: Int = 0
) {

    private class Room(
        val channel: RealtimeChannel,
        val jobs: MutableList<Job> = mutableListOf(),
        /** presenceKey → tripId */
        val riders: MutableMap<String, String> = mutableMapOf()
    )

    private val rooms = LinkedHashMap<String, Room>()
    private val mutex = Mutex()

    private var presenceRoom: String? = null
    private var trackedRoom: String? = null
    private var lastPresenceTrackMs = 0L

    /** Feed every GPS fix: reconciles rooms (open road) and throttles presence (both modes). */
    fun onLocation(fix: LocationEngine.Fix) {
        val wanted: Set<String>
        val own: String
        if (packTag != null) {
            own = PelotonTag.packChannel(packTag)
            wanted = setOf(own)
        } else {
            val cell = H3Provider.cellAddress(fix.lat, fix.lng, GEO_RES) ?: return
            own = PelotonTag.geoChannel(cell)
            wanted = H3Provider.gridDisk(cell, 1).ifEmpty { listOf(cell) }
                .map(PelotonTag::geoChannel).toSet()
        }
        scope.launch {
            mutex.withLock {
                if (wanted != rooms.keys) reconcileLocked(wanted)
                presenceRoom = own
                maybeTrackPresenceLocked(fix)
            }
        }
    }

    /** PROTOCOL §16 publish: the pack channel, or the own geo cell. */
    suspend fun broadcast(payload: BurstPayload) {
        val message = BurstPayload.json
            .encodeToJsonElement(BurstPayload.serializer(), payload)
            .jsonObject
        val target = mutex.withLock { presenceRoom?.let { rooms[it]?.channel } } ?: return
        target.broadcast(event = BURST_EVENT, message = message)
    }

    private suspend fun reconcileLocked(wanted: Set<String>) {
        for (name in rooms.keys.filter { it !in wanted }) {
            val room = rooms.remove(name) ?: continue
            room.jobs.forEach { it.cancel() }
            if (trackedRoom == name) trackedRoom = null
            try {
                client.realtime.removeChannel(room.channel)
            } catch (e: Exception) {
                Log.w(TAG, "removeChannel($name) failed", e)
            }
        }
        for (name in wanted) {
            if (!rooms.containsKey(name)) joinLocked(name)
        }
        recomputeRiders()
    }

    private suspend fun joinLocked(name: String) {
        try {
            val ch = client.channel(name) {
                broadcast {
                    receiveOwnBroadcasts = false
                    acknowledgeBroadcasts = false
                }
            }
            val room = Room(ch)
            room.jobs += ch.broadcastFlow<JsonObject>(event = BURST_EVENT)
                .onEach { json -> BurstPayload.decodeOrNull(json.toString())?.let(onBurst) }
                .launchIn(scope)
            room.jobs += ch.presenceChangeFlow()
                .onEach { action -> applyPresence(room, action) }
                .launchIn(scope)
            ch.subscribe()
            rooms[name] = room
        } catch (e: Exception) {
            Log.e(TAG, "subscribe $name failed", e)
        }
    }

    private fun applyPresence(room: Room, action: PresenceAction) {
        action.leaves.forEach { (key, _) -> room.riders.remove(key) }
        action.joins.forEach { (key, presence) ->
            val tripId = presence.state["trip_id"]?.jsonPrimitive?.content ?: return@forEach
            room.riders[key] = tripId
        }
        recomputeRiders()
    }

    private fun recomputeRiders() {
        val self = selfTripId()
        val distinct = rooms.values.flatMap { it.riders.values }.filter { it != self }.toSet()
        val count = distinct.size + extraRiders
        PelotonState.riderCount.value = count
        onRiderCountChanged(count)
    }

    private suspend fun maybeTrackPresenceLocked(fix: LocationEngine.Fix) {
        val tripId = selfTripId() ?: return
        val name = presenceRoom ?: return
        val room = rooms[name] ?: return
        val now = System.currentTimeMillis()
        val moved = trackedRoom != name
        if (!moved && now - lastPresenceTrackMs < Constants.PRESENCE_THROTTLE_MS) return
        try {
            if (moved) trackedRoom?.let { old -> rooms[old]?.channel?.untrack() }
            room.channel.track(
                buildJsonObject {
                    put("trip_id", tripId)
                    put("handle", selfHandle())
                    put("heading", fix.headingDeg)
                    put("speed", fix.speedMps)
                    put("kind", PRESENCE_KIND)
                }
            )
            trackedRoom = name
            lastPresenceTrackMs = now
        } catch (e: Exception) {
            Log.w(TAG, "presence track failed", e)
        }
    }

    suspend fun shutdown() {
        mutex.withLock {
            for (room in rooms.values) {
                room.jobs.forEach { it.cancel() }
                try {
                    client.realtime.removeChannel(room.channel)
                } catch (_: Exception) {
                }
            }
            rooms.clear()
        }
    }

    companion object {
        private const val TAG = "PelotonChannels"
        private const val BURST_EVENT = "burst"
        private const val PRESENCE_KIND = "rider"
        const val GEO_RES = 8
    }
}
