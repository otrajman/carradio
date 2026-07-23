package com.carradio.app.core

/**
 * PROTOCOL §3 room fan-out math. Pure Kotlin: H3 conversion is injected as a lambda so the
 * cell selection logic is JVM-testable without native H3 bindings.
 */
object RoomMath {

    const val ROOM_PREFIX = "room:"
    const val SAMPLE_STEP_METERS = 1200.0
    const val MAX_PUBLISH_CELLS = 4

    fun roomName(res7Cell: String): String = ROOM_PREFIX + res7Cell

    /**
     * Publish set: own res-7 cell + res-7 cells intersecting the forward ray — points sampled
     * every 1.2 km along the sender's heading out to senderRadius(speed), deduped, capped at 4.
     *
     * @param toRes7Cell converts (lat, lng) → lowercase-hex res-7 H3 address.
     */
    fun publishCells(
        lat: Double,
        lng: Double,
        headingDeg: Double,
        speedMps: Double,
        toRes7Cell: (Double, Double) -> String
    ): List<String> {
        val cells = LinkedHashSet<String>()
        cells.add(toRes7Cell(lat, lng))
        val radius = GeoMath.senderRadiusMeters(speedMps)
        var dist = SAMPLE_STEP_METERS
        while (dist <= radius && cells.size < MAX_PUBLISH_CELLS) {
            val (pLat, pLng) = GeoMath.destinationPoint(lat, lng, headingDeg, dist)
            cells.add(toRes7Cell(pLat, pLng))
            dist += SAMPLE_STEP_METERS
        }
        return cells.toList().take(MAX_PUBLISH_CELLS)
    }
}
