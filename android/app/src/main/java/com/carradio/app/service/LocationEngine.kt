package com.carradio.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.core.GeoMath
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * GPS fix source: FusedLocationProvider at a 2 s cadence (PROTOCOL/foreground service spec),
 * or — behind the settings "fake GPS" flag — a bundled demo route replayed for indoor testing.
 */
class LocationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val useFakeRoute: Boolean,
    private val onFix: (Fix) -> Unit
) {

    /** Units per PROTOCOL §2: m/s, degrees clockwise from true north, WGS84 degrees. */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val headingDeg: Double,
        val speedMps: Double
    )

    private var fused: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var fakeJob: Job? = null
    private var lastHeading = 0.0

    fun start() {
        if (useFakeRoute) startFakeRoute() else startFused()
    }

    fun stop() {
        callback?.let { fused?.removeLocationUpdates(it) }
        callback = null
        fused = null
        fakeJob?.cancel()
        fakeJob = null
    }

    @SuppressLint("MissingPermission") // permission is checked before the service starts
    private fun startFused() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            Constants.LOCATION_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(Constants.LOCATION_INTERVAL_MS / 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.hasBearing()) lastHeading = GeoMath.normalizeDegrees(loc.bearing.toDouble())
                onFix(
                    Fix(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        headingDeg = lastHeading,
                        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                    )
                )
            }
        }
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            fused = client
            callback = cb
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing", e)
        }
    }

    // --- Fake GPS demo route -------------------------------------------------------------

    private fun startFakeRoute() {
        fakeJob = scope.launch {
            var traveled = 0.0
            while (isActive) {
                val fix = fixAlongRoute(traveled)
                onFix(fix)
                traveled += FAKE_SPEED_MPS * (Constants.LOCATION_INTERVAL_MS / 1000.0)
                if (traveled >= routeLength) traveled = 0.0 // loop
                delay(Constants.LOCATION_INTERVAL_MS)
            }
        }
    }

    private fun fixAlongRoute(distanceM: Double): Fix {
        var remaining = distanceM
        for (i in 0 until FAKE_ROUTE.size - 1) {
            val (aLat, aLng) = FAKE_ROUTE[i]
            val (bLat, bLng) = FAKE_ROUTE[i + 1]
            val seg = GeoMath.haversineMeters(aLat, aLng, bLat, bLng)
            if (remaining <= seg) {
                val bearing = GeoMath.bearingDegrees(aLat, aLng, bLat, bLng)
                val (lat, lng) = GeoMath.destinationPoint(aLat, aLng, bearing, remaining)
                return Fix(lat, lng, bearing, FAKE_SPEED_MPS)
            }
            remaining -= seg
        }
        val (lat, lng) = FAKE_ROUTE.last()
        return Fix(lat, lng, lastHeading, FAKE_SPEED_MPS)
    }

    private val routeLength: Double by lazy {
        FAKE_ROUTE.zipWithNext().sumOf { (a, b) ->
            GeoMath.haversineMeters(a.first, a.second, b.first, b.second)
        }
    }

    companion object {
        private const val TAG = "LocationEngine"

        /** ~55 mph. */
        private const val FAKE_SPEED_MPS = 24.6

        /** Approximate waypoints along US-101 South, San Francisco → South San Francisco. */
        private val FAKE_ROUTE: List<Pair<Double, Double>> = listOf(
            37.7695 to -122.4032,
            37.7601 to -122.4057,
            37.7502 to -122.4030,
            37.7402 to -122.4051,
            37.7331 to -122.4048,
            37.7213 to -122.4054,
            37.7100 to -122.4013,
            37.7003 to -122.3939,
            37.6858 to -122.3990,
            37.6706 to -122.3936,
            37.6552 to -122.4054,
            37.6432 to -122.4113,
            37.6295 to -122.4110,
            37.6153 to -122.4030,
            37.6006 to -122.3928
        )
    }
}
