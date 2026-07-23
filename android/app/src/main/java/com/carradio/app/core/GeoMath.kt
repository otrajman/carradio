package com.carradio.app.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geographic math shared by the filter and room fan-out. No Android dependencies.
 * All formulas follow docs/PROTOCOL.md exactly (units: meters, m/s, degrees from true north).
 */
object GeoMath {

    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_MILE = 1609.34
    const val MPS_TO_MPH = 2.236936

    fun degToRad(deg: Double): Double = deg * PI / 180.0
    fun radToDeg(rad: Double): Double = rad * 180.0 / PI

    /** Great-circle distance in meters. */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = degToRad(lat2 - lat1)
        val dLng = degToRad(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(degToRad(lat1)) * cos(degToRad(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north, [0, 360). */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = degToRad(lat1)
        val phi2 = degToRad(lat2)
        val dLng = degToRad(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        return normalizeDegrees(radToDeg(atan2(y, x)))
    }

    /** Destination point given start, bearing (deg) and distance (m). Returns (lat, lng). */
    fun destinationPoint(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        distanceM: Double
    ): Pair<Double, Double> {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = degToRad(bearingDeg)
        val phi1 = degToRad(lat)
        val lambda1 = degToRad(lng)
        val phi2 = asin(
            sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        )
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        var lngOut = radToDeg(lambda2)
        while (lngOut > 180.0) lngOut -= 360.0
        while (lngOut < -180.0) lngOut += 360.0
        return radToDeg(phi2) to lngOut
    }

    fun normalizeDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Heading alignment (PROTOCOL §5.4):
     * dot = sin(Sh)·sin(Rh) + cos(Sh)·cos(Rh) = cos(Sh − Rh), headings in degrees.
     */
    fun headingDot(headingA: Double, headingB: Double): Double {
        val a = degToRad(headingA)
        val b = degToRad(headingB)
        return sin(a) * sin(b) + cos(a) * cos(b)
    }

    /**
     * Sender-owned forward radius (PROTOCOL §5.5):
     * miles = 0.5 + (mph − 15) × (2.5 / 60), clamped to [0.5, 3.0]; radius in meters.
     * 15 mph → 0.5 mi, 75 mph → 3 mi.
     */
    fun senderRadiusMeters(speedMps: Double): Double {
        val mph = speedMps * MPS_TO_MPH
        val miles = (0.5 + (mph - 15.0) * (2.5 / 60.0)).coerceIn(0.5, 3.0)
        return miles * METERS_PER_MILE
    }
}
