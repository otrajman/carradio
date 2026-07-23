package com.carradio.app.core

/**
 * PROTOCOL §7 elastic (density-fallback) mode. Pure Kotlin with an injectable clock.
 *
 * Enter: for 3 continuous minutes, presence across subscribed rooms shows 0 other
 * non-system members AND no bursts passed the filter.
 * Exit: immediately when a burst passes the strict filter or presence shows ≥ 2 peers.
 */
class ElasticModeTracker(
    private val enterAfterMs: Long = 3 * 60 * 1000L
) {

    var elastic: Boolean = false
        private set

    private var lonelySinceMs: Long? = null
    private var peerCount: Int = 0

    fun onPresence(nonSystemPeerCount: Int, nowMs: Long) {
        peerCount = nonSystemPeerCount
        if (nonSystemPeerCount >= 2) {
            elastic = false
            lonelySinceMs = null
        } else if (nonSystemPeerCount > 0) {
            // 1 peer: not lonely enough to start the timer, not busy enough to force-exit.
            lonelySinceMs = null
        }
        tick(nowMs)
    }

    /** Call when a burst passes the STRICT filter (not an elastic-only pass). */
    fun onStrictBurstPassed(nowMs: Long) {
        elastic = false
        lonelySinceMs = null
        tick(nowMs)
    }

    /** Call periodically (e.g. every few seconds). Returns current mode. */
    fun tick(nowMs: Long): Boolean {
        if (elastic) return true
        if (peerCount == 0) {
            val since = lonelySinceMs ?: nowMs.also { lonelySinceMs = it }
            if (nowMs - since >= enterAfterMs) elastic = true
        }
        return elastic
    }
}
