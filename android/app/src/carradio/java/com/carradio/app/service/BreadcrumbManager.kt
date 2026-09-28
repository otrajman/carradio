package com.carradio.app.service

import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.audio.PlaybackQueue
import com.carradio.app.core.BurstFilter
import com.carradio.app.core.BurstPayload
import com.carradio.app.core.GeoMath
import com.carradio.app.data.BreadcrumbRow
import com.carradio.app.data.Repository
import com.carradio.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * PROTOCOL §6 cold-start breadcrumbs.
 *
 * - On every res-8 cell change: RPC get_breadcrumbs (radius = max(1600, senderRadius(speed))).
 * - Played ids persist in DataStore (cap 2000) and survive restarts.
 * - Candidates run through the §5 filter minus the age drop, then trickle out at most one
 *   per 45 s so live traffic always wins.
 * - Breadcrumb earcon (double pop); system texts older than 1 h get an "Earlier here: " prefix.
 */
class BreadcrumbManager(
    private val scope: CoroutineScope,
    private val repository: Repository,
    private val settings: SettingsStore,
    private val filter: BurstFilter,
    private val queue: PlaybackQueue,
    private val selfTripId: () -> String?,
    private val receiverState: () -> BurstFilter.ReceiverState?
) {

    private val mutex = Mutex()
    private var playedIds: MutableSet<String> = mutableSetOf()
    private var loaded = false
    private var lastRes8Cell: String? = null
    private val pending = ArrayDeque<PlaybackQueue.QueuedBurst>()
    private var lastBreadcrumbAtMs = 0L

    fun onFix(fix: LocationEngine.Fix) {
        val cell = H3Provider.cellAddress(fix.lat, fix.lng, Constants.H3_RES_BREADCRUMB) ?: return
        if (cell == lastRes8Cell) return
        lastRes8Cell = cell
        scope.launch { fetchForCell(fix) }
    }

    private suspend fun fetchForCell(fix: LocationEngine.Fix) {
        val tripId = selfTripId() ?: return
        val receiver = receiverState() ?: return
        ensureLoaded()

        val radius = maxOf(1600.0, GeoMath.senderRadiusMeters(fix.speedMps))
        val rows = repository.getBreadcrumbs(
            tripId = tripId,
            lat = fix.lat,
            lng = fix.lng,
            radiusM = radius,
            sinceHours = 24,
            limit = 10,
            convoyTag = receiver.convoyTag
        )
        if (rows.isEmpty()) return

        mutex.withLock {
            for (row in rows) {
                if (row.id in playedIds) continue
                if (pending.any { it.payload.messageId == row.id }) continue
                val payload = row.toPayload()
                // §5 filter minus the age drop; dedupe against the persistent set (not the LRU).
                val decision = filter.evaluate(
                    payload, receiver,
                    elasticMode = false,
                    recordSeen = false
                )
                if (decision != BurstFilter.Decision.PLAY) continue
                pending.addLast(
                    PlaybackQueue.QueuedBurst(
                        payload = payload,
                        isBreadcrumb = true,
                        speakPrefix = speakPrefixFor(row)
                    )
                )
            }
        }
    }

    /** Call periodically (a few seconds cadence) from the service tick loop. */
    fun tick() {
        scope.launch {
            val item: PlaybackQueue.QueuedBurst
            mutex.withLock {
                val now = System.currentTimeMillis()
                if (pending.isEmpty()) return@launch
                if (now - lastBreadcrumbAtMs < Constants.BREADCRUMB_MIN_GAP_MS) return@launch
                if (queue.currentPayload != null) return@launch // live traffic wins
                item = pending.removeFirst()
                lastBreadcrumbAtMs = now
                playedIds.add(item.payload.messageId)
            }
            settings.addPlayedBreadcrumbId(item.payload.messageId)
            queue.enqueue(item)
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (!loaded) {
                playedIds = settings.loadPlayedBreadcrumbIds().toMutableSet()
                loaded = true
            }
        }
    }

    private fun speakPrefixFor(row: BreadcrumbRow): String? {
        if (row.kind != BurstPayload.KIND_SYSTEM && row.audioPath != null) return null
        val ageMs = try {
            System.currentTimeMillis() - Instant.parse(row.createdAt).toEpochMilli()
        } catch (e: Exception) {
            Log.d(TAG, "unparseable created_at: ${row.createdAt}")
            0L
        }
        return if (ageMs > 60 * 60 * 1000L) "Earlier here: " else null
    }

    private fun BreadcrumbRow.toPayload() = BurstPayload(
        messageId = id,
        tripId = tripId,
        handle = handle,
        kind = kind,
        audioPath = audioPath,
        text = text,
        lat = lat,
        lng = lng,
        heading = heading,
        speed = speed,
        h3R9 = "",
        createdAt = createdAt
    )

    companion object {
        private const val TAG = "BreadcrumbManager"
    }
}
