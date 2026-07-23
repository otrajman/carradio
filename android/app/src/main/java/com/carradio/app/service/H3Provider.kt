package com.carradio.app.service

import android.os.Build
import android.util.Log
import com.uber.h3core.H3Core
import com.uber.h3core.H3CoreLoader

/**
 * Lazy holder for Uber H3 (native JNI bindings, com.uber:h3:4.1.1).
 *
 * H3Core.newInstance() auto-detects the OS from os.name — which reports "Linux" on Android
 * and would load glibc binaries. We therefore try the explicit Android natives first for
 * each supported ABI, then fall back to auto-detection. If nothing loads (e.g. an x86_64
 * emulator without bundled natives) the app degrades: no rooms/breadcrumbs, status message
 * shown, everything else keeps running.
 */
object H3Provider {

    @Volatile
    private var core: H3Core? = null

    @Volatile
    private var failed = false

    val isAvailable: Boolean
        get() = get() != null

    fun get(): H3Core? {
        core?.let { return it }
        if (failed) return null
        synchronized(this) {
            core?.let { return it }
            if (failed) return null
            val loaded = load()
            if (loaded == null) failed = true
            core = loaded
            return loaded
        }
    }

    /** Lowercase-hex cell address at the given resolution, or null when H3 is unavailable. */
    fun cellAddress(lat: Double, lng: Double, res: Int): String? = try {
        get()?.latLngToCellAddress(lat, lng, res)?.lowercase()
    } catch (e: Exception) {
        Log.w(TAG, "latLngToCellAddress failed", e)
        null
    }

    /** K-ring (grid disk) of cell addresses, including the origin. */
    fun gridDisk(cellAddress: String, k: Int): List<String> = try {
        get()?.gridDisk(cellAddress, k)?.map { it.lowercase() } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "gridDisk failed", e)
        emptyList()
    }

    private fun load(): H3Core? {
        // The h3-4.1.1 jar bundles exactly two Android natives: /android-arm64/ and
        // /android-arm/ (verified against the Maven Central artifact). x86/x86_64 emulator
        // images have no Android natives and will fall through to degraded mode.
        val archCandidates = Build.SUPPORTED_ABIS.orEmpty().mapNotNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a" -> "arm"
                else -> null
            }
        }.distinct()
        for (arch in archCandidates) {
            try {
                return H3Core.newInstance(H3CoreLoader.OperatingSystem.ANDROID, arch)
            } catch (t: Throwable) {
                Log.d(TAG, "H3 android/$arch load failed: ${t.message}")
            }
        }
        return try {
            H3Core.newInstance()
        } catch (t: Throwable) {
            Log.e(TAG, "H3 native library unavailable on this device", t)
            null
        }
    }

    private const val TAG = "H3Provider"
}
