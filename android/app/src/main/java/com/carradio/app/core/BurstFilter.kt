package com.carradio.app.core

/**
 * PROTOCOL §5 receive filter. Pure Kotlin, NO Android dependencies — unit-tested on the JVM.
 *
 * Runs the checks in the exact protocol order:
 *   1. self  2. dedupe (LRU ~200)  3. mute  4. heading match  5. distance / forward cone
 * (§5.6 age-drop at dequeue is playback-queue policy, not filter policy.)
 */
class BurstFilter(
    private val maxSeenIds: Int = 200
) {

    enum class Decision {
        PLAY,
        DROP_SELF,
        DROP_DUPLICATE,
        DROP_MUTED,
        DROP_CONVOY,
        DROP_HEADING,
        DROP_DISTANCE
    }

    /** Receiver's current GPS state. Speed m/s, heading degrees from true north. */
    data class ReceiverState(
        val tripId: String,
        val lat: Double,
        val lng: Double,
        val heading: Double,
        val speedMps: Double,
        /** §14: hashed convoy tag when in convoy mode, else null. */
        val convoyTag: String? = null
    )

    companion object {
        const val HEADING_DOT_THRESHOLD = 0.85     // ±31.8°
        const val AHEAD_DOT_THRESHOLD = 0.5        // ±60°
        const val NEAR_BUBBLE_METERS = 800.0
        const val MIN_SPEED_FOR_HEADING_MPS = 3.0  // below this, heading is GPS noise
    }

    private val seenIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > maxSeenIds
    }

    private val mutedTrips = LinkedHashSet<String>()

    fun mute(tripId: String) {
        mutedTrips.add(tripId)
    }

    fun isMuted(tripId: String): Boolean = tripId in mutedTrips

    fun mutedTrips(): Set<String> = mutedTrips.toSet()

    /** For breadcrumb replay paths that dedupe externally (persistent played-ids set). */
    fun hasSeen(messageId: String): Boolean = seenIds.containsKey(messageId)

    /**
     * Evaluate one incoming burst against the receiver state.
     *
     * @param elasticMode PROTOCOL §7: skip all directional/distance gating (self/dedupe/mute
     *                    still apply); reach is bounded by the subscribed rooms.
     * @param recordSeen  when true (live bursts) the message id is added to the dedupe LRU.
     */
    fun evaluate(
        burst: BurstPayload,
        receiver: ReceiverState,
        elasticMode: Boolean = false,
        recordSeen: Boolean = true
    ): Decision {
        // 1. Self
        if (burst.tripId == receiver.tripId) return Decision.DROP_SELF

        // 2. Dedupe (senders fan out to multiple rooms)
        if (seenIds.containsKey(burst.messageId)) return Decision.DROP_DUPLICATE
        if (recordSeen) seenIds[burst.messageId] = true

        // 3. Mute
        if (burst.tripId in mutedTrips) return Decision.DROP_MUTED

        // §14 convoy: members hear only each other (plus system alerts); outsiders
        // never hear convoy traffic.
        val myTag = receiver.convoyTag
        if (myTag != null) {
            if (burst.convoy == myTag) return Decision.PLAY
            if (!burst.isSystem) return Decision.DROP_CONVOY
        } else if (burst.convoy != null) {
            return Decision.DROP_CONVOY
        }

        return geometryDecision(burst, receiver, elasticMode)
    }

    /**
     * Stateless geometry check (§5.4–§5.5 only, strict rules). Used to detect "a burst passed
     * the strict filter" for elastic-mode exit (§7) without touching the dedupe LRU.
     */
    fun passesStrictGeometry(burst: BurstPayload, receiver: ReceiverState): Boolean =
        geometryDecision(burst, receiver, elasticMode = false) == Decision.PLAY

    private fun geometryDecision(
        burst: BurstPayload,
        receiver: ReceiverState,
        elasticMode: Boolean
    ): Decision {
        // 4. Heading match (skipped when parked/creeping, in elastic mode, or for system bursts)
        val skipHeading = receiver.speedMps < MIN_SPEED_FOR_HEADING_MPS ||
            elasticMode || burst.isSystem
        if (!skipHeading) {
            val dot = GeoMath.headingDot(burst.heading, receiver.heading)
            if (dot < HEADING_DOT_THRESHOLD) return Decision.DROP_HEADING
        }

        // §7: elastic mode drops all directional/distance gating — reach is bounded by
        // the subscribed rooms themselves. Matches PWA and iOS.
        if (elasticMode) return Decision.PLAY

        // 5. Distance / forward cone (asymmetric, sender-owned cone)
        val d = GeoMath.haversineMeters(burst.lat, burst.lng, receiver.lat, receiver.lng)
        if (d <= NEAR_BUBBLE_METERS) return Decision.PLAY

        // Receiver must be ahead of the sender.
        val bearing = GeoMath.bearingDegrees(burst.lat, burst.lng, receiver.lat, receiver.lng)
        val aheadDot = kotlin.math.cos(GeoMath.degToRad(bearing - burst.heading))
        if (aheadDot < AHEAD_DOT_THRESHOLD) return Decision.DROP_DISTANCE

        if (d > GeoMath.senderRadiusMeters(burst.speed)) return Decision.DROP_DISTANCE

        return Decision.PLAY
    }
}
