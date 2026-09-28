package com.carradio.app.peloton.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carradio.app.peloton.PelotonState
import com.carradio.app.peloton.PelotonState.Mic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-ride screen for a handlebar-mounted phone. One glance answers: am I on air, who's
 * talking, how many are with me. One giant target pauses/resumes the mic; everything else
 * (skip, report, leave) is secondary and also reachable from earbud buttons.
 */
@Composable
fun RideScreen(
    onTogglePause: () -> Unit,
    onSkipMute: () -> Unit,
    onReport: () -> Unit,
    onShareCode: () -> Unit,
    onLeave: () -> Unit
) {
    val c = Peloton.colors
    val mic by PelotonState.mic.collectAsStateWithLifecycle()
    val level by PelotonState.micLevel.collectAsStateWithLifecycle()
    val riders by PelotonState.riderCount.collectAsStateWithLifecycle()
    val packCode by PelotonState.packCode.collectAsStateWithLifecycle()
    val handle by PelotonState.handle.collectAsStateWithLifecycle()
    val speaker by PelotonState.speakerHandle.collectAsStateWithLifecycle()
    val lastSpeaker by PelotonState.lastSpeakerHandle.collectAsStateWithLifecycle()
    val playing by PelotonState.isPlaying.collectAsStateWithLifecycle()
    val sent by PelotonState.snippetsSent.collectAsStateWithLifecycle()
    val status by PelotonState.statusMessage.collectAsStateWithLifecycle()
    val headset by PelotonState.headsetMic.collectAsStateWithLifecycle()

    val paused = mic == Mic.PAUSED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Top: where am I riding, and with how many ---------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (packCode != null) Modifier.clickable(onClick = onShareCode) else Modifier)
                    .padding(vertical = 4.dp)
            ) {
                Eyebrow(if (packCode != null) "Pack · tap to share" else "Open road · 500 m")
                Text(
                    text = packCode ?: "ANY RIDER NEARBY",
                    fontFamily = if (packCode != null) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$riders",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    color = if (riders > 0) c.pack else c.muted,
                    lineHeight = 40.sp
                )
                Eyebrow(if (riders == 1) "rider" else "riders")
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- Center: VOX dial ------------------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            VoxDial(
                mic = mic,
                level = level,
                playing = playing,
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .aspectRatio(1f)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val (label, color) = when {
                    playing -> "INCOMING" to c.pack
                    mic == Mic.ON_AIR -> "ON AIR" to c.signal
                    mic == Mic.LISTENING -> "LISTENING" to c.ink
                    mic == Mic.YIELDING -> "INCOMING" to c.pack
                    mic == Mic.PAUSED -> "MIC PAUSED" to c.muted
                    else -> "CONNECTING" to c.muted
                }
                Text(label, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 1.sp)
                Text(
                    text = when {
                        playing && speaker != null -> speaker!!
                        mic == Mic.ON_AIR -> "the pack hears you"
                        mic == Mic.LISTENING -> "just talk"
                        paused -> "you still hear the pack"
                        else -> " "
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.muted,
                    textAlign = TextAlign.Center
                )
            }
        }

        status?.let {
            Text(
                text = it,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = c.signal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // --- Primary: pause / resume transmit -----------------------------------------------
        BigButton(
            label = if (paused) "RESUME MIC" else "PAUSE MIC",
            background = if (paused) c.signal else c.ink,
            content = if (paused) c.onSignal else c.background,
            onClick = onTogglePause,
            height = 112,
            fontSize = 30,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BigButton(
                label = "SKIP + MUTE",
                background = c.surface,
                content = c.ink,
                border = c.line,
                onClick = onSkipMute,
                enabled = playing || lastSpeaker != null,
                fontSize = 16,
                modifier = Modifier.weight(1f)
            )
            BigButton(
                label = "REPORT",
                background = c.surface,
                content = c.ink,
                border = c.line,
                onClick = onReport,
                enabled = playing || lastSpeaker != null,
                fontSize = 16,
                modifier = Modifier.weight(1f)
            )
            BigButton(
                label = "LEAVE",
                background = c.surface,
                content = c.muted,
                border = c.line,
                onClick = onLeave,
                fontSize = 16,
                modifier = Modifier.weight(0.8f)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = buildString {
                append("You're ${handle.ifBlank { "…" }}")
                append(" · $sent sent")
                if (headset) append(" · headset mic")
            },
            fontSize = 13.sp,
            color = c.muted,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Earbud tap: pause · next: skip + mute",
            fontSize = 12.sp,
            color = c.muted.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 60 radial ticks. Lit ticks follow the live mic level (orange on air, ink listening);
 * while the pack plays, a green sweep circles instead; paused, the ring rests dim.
 */
@Composable
private fun VoxDial(mic: Mic, level: Float, playing: Boolean, modifier: Modifier = Modifier) {
    val c = Peloton.colors
    val animatedLevel by animateFloatAsState(level, tween(90), label = "level")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val active: Color = when {
        playing || mic == Mic.YIELDING -> c.pack
        mic == Mic.ON_AIR -> c.signal
        mic == Mic.LISTENING -> c.ink
        else -> c.muted
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f
        val inner = outer * 0.80f
        val ticks = 60
        val lit = (animatedLevel * ticks).toInt()
        for (i in 0 until ticks) {
            val angle = (i.toFloat() / ticks) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val on = when {
                playing || mic == Mic.YIELDING -> {
                    val head = (sweep * ticks).toInt()
                    val d = (head - i + ticks) % ticks
                    d < 14
                }
                mic == Mic.PAUSED || mic == Mic.OFF -> false
                else -> i < lit
            }
            val major = i % 5 == 0
            val r0 = if (major) inner * 0.94f else inner
            val color = if (on) active else c.line
            drawLine(
                color = color,
                start = Offset(center.x + cos(angle) * r0, center.y + sin(angle) * r0),
                end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                strokeWidth = if (major) outer * 0.035f else outer * 0.022f,
                cap = StrokeCap.Round
            )
        }
        if (mic == Mic.ON_AIR) {
            drawCircle(color = c.signal.copy(alpha = 0.12f + 0.25f * animatedLevel), radius = inner * 0.86f, center = center)
        }
    }
}
