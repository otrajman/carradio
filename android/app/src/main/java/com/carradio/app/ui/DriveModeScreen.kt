package com.carradio.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carradio.app.Constants
import com.carradio.app.service.RadioState
import com.carradio.app.ui.theme.RadioAmber
import com.carradio.app.ui.theme.RadioDim
import com.carradio.app.ui.theme.RadioGreen
import com.carradio.app.ui.theme.RadioRed
import com.carradio.app.ui.theme.RadioTextDim
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PROTOCOL §13 Drive Mode: dark-only, giant tap-to-talk button, swipe-down to skip + stealth
 * mute, phonetic handle display, pulsing density ring. Locks (no settings, no exit) while
 * driving; an 8 s "I'm a passenger" long-press on the lock banner unlocks.
 */
@Composable
fun DriveModeScreen(
    onToggleTalk: () -> Unit,
    onSkipMute: () -> Unit,
    onReport: () -> Unit,
    onGoOnAir: () -> Unit,
    onGoOffAir: () -> Unit,
    onOpenSettings: () -> Unit,
    onPassengerOverride: () -> Unit
) {
    val serviceRunning by RadioState.serviceRunning.collectAsStateWithLifecycle()
    val handle by RadioState.handle.collectAsStateWithLifecycle()
    val peerCount by RadioState.peerCount.collectAsStateWithLifecycle()
    val lastSpeaker by RadioState.lastSpeakerHandle.collectAsStateWithLifecycle()
    val isRecording by RadioState.isRecording.collectAsStateWithLifecycle()
    val isPlaying by RadioState.isPlaying.collectAsStateWithLifecycle()
    val elastic by RadioState.elasticMode.collectAsStateWithLifecycle()
    val locked by RadioState.driveLocked.collectAsStateWithLifecycle()
    val status by RadioState.statusMessage.collectAsStateWithLifecycle()

    val dragTotal = remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(serviceRunning) {
                if (!serviceRunning) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragTotal.floatValue = 0f },
                    onVerticalDrag = { _, dy -> dragTotal.floatValue += dy },
                    onDragEnd = {
                        if (dragTotal.floatValue > SWIPE_THRESHOLD_PX) onSkipMute()
                    }
                )
            },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding() // SDK 35 is edge-to-edge; keep HUD below the status bar
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(
                locked = locked,
                serviceRunning = serviceRunning,
                onOpenSettings = onOpenSettings,
                onGoOffAir = onGoOffAir,
                onPassengerOverride = onPassengerOverride
            )

            Spacer(Modifier.height(8.dp))

            DensityRing(
                peerCount = peerCount,
                isRecording = isRecording,
                isPlaying = isPlaying,
                active = serviceRunning,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (serviceRunning) handle else "Off air",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = when {
                    !serviceRunning -> "Tap below to join the traffic around you"
                    isRecording -> "Broadcasting…"
                    isPlaying -> lastSpeaker?.let { "Now: $it" } ?: "Receiving…"
                    elastic -> "Wide-range mode — quiet roads"
                    lastSpeaker != null -> "Last heard: $lastSpeaker"
                    else -> "Listening for your traffic…"
                },
                fontSize = 16.sp,
                color = RadioTextDim,
                textAlign = TextAlign.Center
            )
            status?.let {
                Text(text = it, fontSize = 14.sp, color = RadioAmber, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.weight(1f))

            TalkButton(
                serviceRunning = serviceRunning,
                isRecording = isRecording,
                onGoOnAir = onGoOnAir,
                onToggleTalk = onToggleTalk,
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (serviceRunning) "Swipe down anywhere: skip + mute" else " ",
                fontSize = 13.sp,
                color = RadioDim,
                textAlign = TextAlign.Center
            )
            if (serviceRunning && lastSpeaker != null) {
                Text(
                    text = "Report speaker",
                    fontSize = 13.sp,
                    color = RadioDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { onReport() }
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    locked: Boolean,
    serviceRunning: Boolean,
    onOpenSettings: () -> Unit,
    onGoOffAir: () -> Unit,
    onPassengerOverride: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (locked) {
            // 8-second deliberate hold to unlock ("I'm a passenger", PROTOCOL §13).
            Text(
                text = "DRIVE MODE — hold 8 s if you're a passenger",
                fontSize = 13.sp,
                color = RadioAmber,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val released = withTimeoutOrNull(
                                    Constants.PASSENGER_LONG_PRESS_MS
                                ) { tryAwaitRelease() }
                                if (released == null) onPassengerOverride()
                            }
                        )
                    }
            )
        } else {
            Spacer(Modifier.weight(1f))
            if (serviceRunning) {
                Text(
                    text = "Off air",
                    fontSize = 15.sp,
                    color = RadioTextDim,
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .clickable { onGoOffAir() }
                )
            }
            Text(
                text = "Settings",
                fontSize = 15.sp,
                color = RadioTextDim,
                modifier = Modifier.clickable { onOpenSettings() }
            )
        }
    }
}

@Composable
private fun DensityRing(
    peerCount: Int,
    isRecording: Boolean,
    isPlaying: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    val ringColor = when {
        isRecording -> RadioRed
        isPlaying -> RadioGreen
        !active -> RadioDim
        else -> RadioGreen.copy(alpha = 0.55f)
    }
    val density = (peerCount.coerceAtMost(8)) / 8f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f

        // Static core dot.
        drawCircle(color = ringColor, radius = maxR * 0.08f, center = center)

        // Pulsing wave — expands and fades. Stronger/brighter with more peers.
        if (active) {
            val waveR = maxR * (0.15f + 0.85f * pulse)
            val alpha = (1f - pulse) * (0.25f + 0.6f * density.coerceAtLeast(0.15f))
            drawCircle(
                color = ringColor.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = waveR,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = maxR * 0.045f
                )
            )
        }

        // Fixed reference ring.
        drawCircle(
            color = ringColor.copy(alpha = 0.35f),
            radius = maxR * 0.95f,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxR * 0.02f)
        )
    }
}

@Composable
private fun TalkButton(
    serviceRunning: Boolean,
    isRecording: Boolean,
    onGoOnAir: () -> Unit,
    onToggleTalk: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg: Color = when {
        !serviceRunning -> RadioGreen
        isRecording -> RadioRed
        else -> MaterialTheme.colorScheme.surface
    }
    val label = when {
        !serviceRunning -> "GO ON AIR"
        isRecording -> "SENDING…\nTAP TO STOP"
        else -> "TAP TO TALK"
    }
    val textColor = when {
        !serviceRunning -> MaterialTheme.colorScheme.onPrimary
        isRecording -> MaterialTheme.colorScheme.onPrimary
        else -> RadioGreen
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable { if (serviceRunning) onToggleTalk() else onGoOnAir() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = textColor,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp
        )
    }
}

private const val SWIPE_THRESHOLD_PX = 220f
