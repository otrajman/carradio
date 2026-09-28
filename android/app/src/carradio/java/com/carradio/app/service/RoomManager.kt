package com.carradio.app.service

import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.core.BurstPayload
import com.carradio.app.core.RoomMath
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
 * PROTOCOL §3 realtime room manager.
 *
 * - Rooms are `room:<h3_res7_index>` public broadcast channels.
 * - Subscribe set: gridDisk(own res-7 cell, 1) → up to 7 channels, recomputed per cell change.
 * - Publish set: own cell + forward-ray cells (RoomMath), broadcast fan-out capped at 4.
 * - Presence: tracked only on the OWN cell's channel, throttled to 1 update / 10 s.
 */
class RoomManager(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
    private val selfTripId: () -> String?,
    private val selfHandle: () -> String,
    private val onBurst: (BurstPayload) -> Unit,
    private val onPeerCountChanged: (Int) -> Unit,
    private val onRes7CellEntered: (cell: String, fix: LocationEngine.Fix) -> Unit
) {

    private class Room(
        val cell: String,
        val channel: RealtimeChannel,
        val jobs: MutableList<Job> = mutableListOf(),
        /** presenceKey → (tripId, kind) */
        val peers: MutableMap<String, Pair<String, String>> = mutableMapOf()
    )

    private val rooms = LinkedHashMap<String, Room>()

    /** Channels used only for forward fan-out publishing (never subscribed). */
    private val publishOnly = LinkedHashMap<String, RealtimeChannel>()

    private val mutex = Mutex()

    private var ownCell: String? = null
    private var trackedCell: String? = null
    private var lastPresenceTrackMs = 0L

    /** Feed every GPS fix; recomputes rooms on res-7 cell change and throttles presence. */
    fun onLocation(fix: LocationEngine.Fix) {
        val cell = H3Provider.cellAddress(fix.lat, fix.lng, Constants.H3_RES_ROOMS) ?: return
        scope.launch {
            mutex.withLock {
                if (cell != ownCell) {
                    ownCell = cell
                    RadioState.currentRoomCell.value = cell
                    resubscribeLocked(cell)
                    onRes7CellEntered(cell, fix)
                }
                maybeTrackPresenceLocked(fix)
            }
        }
    }

    private suspend fun resubscribeLocked(newOwnCell: String) {
        val wanted = H3Provider.gridDisk(newOwnCell, 1).toSet().ifEmpty { setOf(newOwnCell) }

        // Drop channels that left the subscribe set.
        val stale = rooms.keys.filter { it !in wanted }
        for (cell in stale) {
            val room = rooms.remove(cell) ?: continue
            room.jobs.forEach { it.cancel() }
            try {
                client.realtime.removeChannel(room.channel)
            } catch (e: Exception) {
                Log.w(TAG, "removeChannel($cell) failed", e)
            }
        }

        // Join new ones.
        for (cell in wanted) {
            if (rooms.containsKey(cell)) continue
            joinRoomLocked(cell)
        }
        recomputePeers()
    }

    private suspend fun joinRoomLocked(cell: String) {
        try {
            val ch = client.channel(RoomMath.roomName(cell)) {
                broadcast {
                    receiveOwnBroadcasts = false
                    acknowledgeBroadcasts = false
                }
            }
            val room = Room(cell, ch)

            room.jobs += ch.broadcastFlow<JsonObject>(event = BURST_EVENT)
                .onEach { json ->
                    BurstPayload.decodeOrNull(json.toString())?.let(onBurst)
                }
                .launchIn(scope)

            room.jobs += ch.presenceChangeFlow()
                .onEach { action -> applyPresence(room, action) }
                .launchIn(scope)

            ch.subscribe()
            rooms[cell] = room
        } catch (e: Exception) {
            Log.e(TAG, "subscribe room:$cell failed", e)
        }
    }

    private fun applyPresence(room: Room, action: PresenceAction) {
        action.leaves.forEach { (key, _) -> room.peers.remove(key) }
        action.joins.forEach { (key, presence) ->
            val state = presence.state
            val tripId = state["trip_id"]?.jsonPrimitive?.content ?: return@forEach
            val kind = state["kind"]?.jsonPrimitive?.content ?: "voice"
            room.peers[key] = tripId to kind
        }
        recomputePeers()
    }

    private fun recomputePeers() {
        val self = selfTripId()
        val distinct = rooms.values
            .flatMap { it.peers.values }
            .filter { (tripId, kind) -> kind != BurstPayload.KIND_SYSTEM && tripId != self }
            .map { it.first }
            .toSet()
        onPeerCountChanged(distinct.size)
    }

    /** PROTOCOL §3 presence: own-cell channel only, `{trip_id, handle, heading, speed, kind}`. */
    private suspend fun maybeTrackPresenceLocked(fix: LocationEngine.Fix) {
        val tripId = selfTripId() ?: return
        val cell = ownCell ?: return
        val room = rooms[cell] ?: return
        val now = System.currentTimeMillis()
        val cellChanged = trackedCell != cell
        if (!cellChanged && now - lastPresenceTrackMs < Constants.PRESENCE_THROTTLE_MS) return

        try {
            if (cellChanged) {
                trackedCell?.let { old -> rooms[old]?.channel?.untrack() }
            }
            room.channel.track(
                buildJsonObject {
                    put("trip_id", tripId)
                    put("handle", selfHandle())
                    put("heading", fix.headingDeg)
                    put("speed", fix.speedMps)
                    put("kind", BurstPayload.KIND_VOICE)
                }
            )
            trackedCell = cell
            lastPresenceTrackMs = now
        } catch (e: Exception) {
            Log.w(TAG, "presence track failed", e)
        }
    }

    /**
     * PROTOCOL §4.4 — broadcast the burst to every room in the publish set. Rooms we are
     * subscribed to reuse their channel; forward-only cells use a lightweight channel that is
     * never joined (supabase-kt falls back to the Realtime HTTP broadcast endpoint).
     */
    suspend fun broadcastBurst(payload: BurstPayload) {
        val cells = RoomMath.publishCells(
            payload.lat, payload.lng, payload.heading, payload.speed
        ) { lat, lng -> H3Provider.cellAddress(lat, lng, Constants.H3_RES_ROOMS) ?: "" }
            .filter { it.isNotEmpty() }

        val message = BurstPayload.json
            .encodeToJsonElement(BurstPayload.serializer(), payload)
            .jsonObject

        for (cell in cells) {
            try {
                val ch = mutex.withLock {
                    val subscribed = rooms[cell]?.channel
                    if (subscribed != null) {
                        subscribed
                    } else {
                        val created = publishOnly.getOrPut(cell) {
                            client.channel(RoomMath.roomName(cell))
                        }
                        prunePublishOnlyLocked()
                        created
                    }
                }
                ch.broadcast(event = BURST_EVENT, message = message)
            } catch (e: Exception) {
                Log.w(TAG, "broadcast to room:$cell failed", e)
            }
        }
    }

    private suspend fun prunePublishOnlyLocked() {
        while (publishOnly.size > MAX_PUBLISH_ONLY_CHANNELS) {
            val eldest = publishOnly.entries.first()
            publishOnly.remove(eldest.key)
            try {
                client.realtime.removeChannel(eldest.value)
            } catch (_: Exception) {
            }
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
            for (ch in publishOnly.values) {
                try {
                    client.realtime.removeChannel(ch)
                } catch (_: Exception) {
                }
            }
            publishOnly.clear()
        }
    }

    companion object {
        private const val TAG = "RoomManager"
        private const val BURST_EVENT = "burst"
        private const val MAX_PUBLISH_ONLY_CHANNELS = 6
    }
}
