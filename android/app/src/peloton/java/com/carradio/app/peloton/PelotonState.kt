package com.carradio.app.peloton

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide observable ride state: [PelotonService] writes, the Compose UI reads.
 * Same single-process StateFlow singleton pattern as Car Radio's RadioState.
 */
object PelotonState {

    enum class Mic {
        /** Service not running. */
        OFF,
        /** Rider paused transmit; still hearing the pack. */
        PAUSED,
        /** Mic open, waiting for speech. */
        LISTENING,
        /** Speech detected; a snippet is being captured. */
        ON_AIR,
        /** Half-duplex: someone in the pack is playing; the mic yields. */
        YIELDING
    }

    val serviceRunning = MutableStateFlow(false)
    val handle = MutableStateFlow("")

    /** Display code of the pack ("CLIMB-4821"); null in open-road mode. */
    val packCode = MutableStateFlow<String?>(null)

    /** Other riders present on the pack channel / nearby geo rooms. */
    val riderCount = MutableStateFlow(0)

    val mic = MutableStateFlow(Mic.OFF)

    /** Smoothed live input level, 0..1, for the VOX meter. */
    val micLevel = MutableStateFlow(0f)

    val isPlaying = MutableStateFlow(false)
    val speakerHandle = MutableStateFlow<String?>(null)
    val lastSpeakerHandle = MutableStateFlow<String?>(null)

    val snippetsSent = MutableStateFlow(0)
    val hasFix = MutableStateFlow(false)
    val headsetMic = MutableStateFlow(false)

    /** Human-readable status/problem line ("Waiting for GPS", "Send failed", ...). */
    val statusMessage = MutableStateFlow<String?>(null)

    fun reset() {
        serviceRunning.value = false
        handle.value = ""
        packCode.value = null
        riderCount.value = 0
        mic.value = Mic.OFF
        micLevel.value = 0f
        isPlaying.value = false
        speakerHandle.value = null
        lastSpeakerHandle.value = null
        snippetsSent.value = 0
        hasFix.value = false
        headsetMic.value = false
        statusMessage.value = null
    }
}
