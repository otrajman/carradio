package com.carradio.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.coroutineScope
import com.carradio.app.service.RadioService
import com.carradio.app.service.RadioState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The "radar dashboard" (PRD §5C / PROTOCOL §13 spirit): no maps, dark host theme, a pulsing
 * density indicator, current handle, last speaker, and two actions — Talk and Skip/Mute.
 *
 * Car templates can't animate freely, so the pulse is a text meter re-rendered on state
 * changes (the host renders COMMUNICATION apps on a dark background).
 */
class RadarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        val flows: List<Flow<*>> = listOf(
            RadioState.serviceRunning,
            RadioState.handle,
            RadioState.peerCount,
            RadioState.lastSpeakerHandle,
            RadioState.isRecording,
            RadioState.isPlaying,
            RadioState.elasticMode
        )
        flows.forEach { flow ->
            flow.onEach { invalidate() }.launchIn(lifecycle.coroutineScope)
        }
    }

    override fun onGetTemplate(): Template {
        if (!RadioState.serviceRunning.value) {
            return MessageTemplate.Builder("Start Car Radio on your phone to go on air.")
                .setTitle("Car Radio")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val recording = RadioState.isRecording.value
        val playing = RadioState.isPlaying.value
        val peers = RadioState.peerCount.value

        val statusRow = Row.Builder()
            .setTitle(densityMeter(peers, recording, playing))
            .addText(
                when {
                    recording -> "On air — recording"
                    playing -> "Receiving…"
                    RadioState.elasticMode.value -> "Quiet out here — wide-range mode"
                    peers == 0 -> "Scanning for traffic…"
                    else -> "$peers driver${if (peers == 1) "" else "s"} in your flow"
                }
            )
            .build()

        val handleRow = Row.Builder()
            .setTitle("You are \"${RadioState.handle.value}\"")
            .addText("New handle every trip")
            .build()

        val lastRow = Row.Builder()
            .setTitle("Last heard")
            .addText(RadioState.lastSpeakerHandle.value ?: "Nobody yet")
            .build()

        val talkAction = Action.Builder()
            .setTitle(if (recording) "Stop" else "Talk")
            .setBackgroundColor(if (recording) CarColor.RED else CarColor.GREEN)
            .setOnClickListener {
                RadioService.sendAction(carContext, RadioService.ACTION_TOGGLE_TALK)
            }
            .build()

        val skipAction = Action.Builder()
            .setTitle("Skip / Mute")
            .setOnClickListener {
                RadioService.sendAction(carContext, RadioService.ACTION_SKIP_MUTE)
            }
            .build()

        val pane = Pane.Builder()
            .addRow(statusRow)
            .addRow(handleRow)
            .addRow(lastRow)
            .addAction(talkAction)
            .addAction(skipAction)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Car Radio")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /** Text-rendered pulsing density ring: ● count scales with peers; color comes from state. */
    private fun densityMeter(peers: Int, recording: Boolean, playing: Boolean): String {
        val filled = when {
            peers <= 0 -> 1
            peers >= 8 -> 8
            else -> peers
        }
        val ring = "●".repeat(filled) + "○".repeat(8 - filled)
        val stateGlyph = when {
            recording -> "🔴"
            playing -> "🟢"
            else -> "⚫"
        }
        return "$stateGlyph  $ring"
    }
}
