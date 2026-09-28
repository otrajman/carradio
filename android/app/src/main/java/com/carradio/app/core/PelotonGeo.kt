package com.carradio.app.core

/**
 * PROTOCOL §16 open-road reach: a pack is the riders around you, not a forward cone.
 * Symmetric radius, plus a loose same-direction check so an oncoming group passing by
 * doesn't bleed into your pack. Pure Kotlin, JVM-tested.
 */
object PelotonGeo {

    const val PACK_RADIUS_METERS = 500.0
    const val HEADING_DOT_THRESHOLD = 0.5      // ±60°: switchbacks and bends still match
    const val MIN_SPEED_FOR_HEADING_MPS = 3.0  // below this (regroup stop, climb crawl) heading is noise

    fun inRange(
        senderLat: Double,
        senderLng: Double,
        senderHeading: Double,
        senderSpeedMps: Double,
        receiver: BurstFilter.ReceiverState
    ): Boolean {
        val d = GeoMath.haversineMeters(senderLat, senderLng, receiver.lat, receiver.lng)
        if (d > PACK_RADIUS_METERS) return false
        val bothMoving = senderSpeedMps >= MIN_SPEED_FOR_HEADING_MPS &&
            receiver.speedMps >= MIN_SPEED_FOR_HEADING_MPS
        if (!bothMoving) return true
        return GeoMath.headingDot(senderHeading, receiver.heading) >= HEADING_DOT_THRESHOLD
    }
}
