package com.carradio.app.service

import com.carradio.app.core.H3Lite

/**
 * H3 cell math for rooms/breadcrumbs, backed by the pure-Kotlin port in
 * [H3Lite] (validated against official h3-js v4 fixtures — see H3LiteTest).
 *
 * This used to load the native h3-java JNI bindings (com.uber:h3), but those
 * prebuilt .so files are 4KB-aligned and cannot load on 16KB page-size devices
 * (e.g. Galaxy Z Fold 7 / Android 15+ 16KB mode). The pure-Kotlin port removes
 * the native dependency entirely, so H3 is now always available.
 */
object H3Provider {

    /** Always true — no native library to load anymore. */
    val isAvailable: Boolean
        get() = true

    /** Lowercase-hex cell address at the given resolution, or null for invalid input. */
    fun cellAddress(lat: Double, lng: Double, res: Int): String? = try {
        H3Lite.latLngToCell(lat, lng, res)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** K-ring (grid disk) of cell addresses, including the origin. */
    fun gridDisk(cellAddress: String, k: Int): List<String> = H3Lite.gridDisk(cellAddress, k)
}
