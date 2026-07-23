package com.carradio.app.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide observable state shared by the foreground service (writer), the Compose UI,
 * and the Android Auto screen (readers). Deliberately a plain singleton of StateFlows:
 * everything lives in one process and one service instance.
 */
object RadioState {

    val serviceRunning = MutableStateFlow(false)

    /** Ephemeral identity for this trip. */
    val tripId = MutableStateFlow<String?>(null)
    val handle = MutableStateFlow("...")

    /** Distinct non-system peers visible via presence across subscribed rooms. */
    val peerCount = MutableStateFlow(0)

    val lastSpeakerHandle = MutableStateFlow<String?>(null)
    val lastSpeakerTripId = MutableStateFlow<String?>(null)

    val isRecording = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val elasticMode = MutableStateFlow(false)

    /** PROTOCOL §13 Drive Mode lock. */
    val driveLocked = MutableStateFlow(false)

    val speedMps = MutableStateFlow(0.0)
    val headingDeg = MutableStateFlow(0.0)
    val currentRoomCell = MutableStateFlow<String?>(null)

    val wakeWordActive = MutableStateFlow(false)

    /** Human-readable status/problem line for the UI ("H3 unavailable", "offline", ...). */
    val statusMessage = MutableStateFlow<String?>(null)

    fun reset() {
        serviceRunning.value = false
        tripId.value = null
        handle.value = "..."
        peerCount.value = 0
        lastSpeakerHandle.value = null
        lastSpeakerTripId.value = null
        isRecording.value = false
        isPlaying.value = false
        elasticMode.value = false
        driveLocked.value = false
        speedMps.value = 0.0
        headingDeg.value = 0.0
        currentRoomCell.value = null
        wakeWordActive.value = false
        statusMessage.value = null
    }
}
