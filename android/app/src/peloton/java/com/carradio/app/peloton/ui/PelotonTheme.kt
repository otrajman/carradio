package com.carradio.app.peloton.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PelotonCB look: a race-number / road-sign instrument for a phone on the handlebars.
 * Unlike Car Radio (dark-only, night driving) riders are out in daylight, so the default
 * is a high-contrast paper-and-ink light theme; a dark "asphalt" variant follows the system.
 * Safety orange = you're transmitting; pack green = the pack is talking.
 */
@Immutable
data class PelotonColors(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val signal: Color,
    val onSignal: Color,
    val pack: Color,
    val onPack: Color
)

private val Light = PelotonColors(
    background = Color(0xFFF3F0E8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF111214),
    muted = Color(0xFF66656B),
    line = Color(0xFFD8D2C4),
    signal = Color(0xFFFF4F1F),
    onSignal = Color(0xFF111214),
    pack = Color(0xFF0B8F63),
    onPack = Color(0xFFFFFFFF)
)

private val Dark = PelotonColors(
    background = Color(0xFF0E0F11),
    surface = Color(0xFF1A1B1F),
    ink = Color(0xFFF2F0EA),
    muted = Color(0xFF8E8D94),
    line = Color(0xFF2C2D33),
    signal = Color(0xFFFF6A3D),
    onSignal = Color(0xFF111214),
    pack = Color(0xFF34D399),
    onPack = Color(0xFF0E0F11)
)

val LocalPelotonColors = staticCompositionLocalOf { Light }

object Peloton {
    val colors: PelotonColors
        @Composable get() = LocalPelotonColors.current
}

@Composable
fun PelotonTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = c.signal, onPrimary = c.onSignal, secondary = c.pack,
            background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, outline = c.line
        )
    } else {
        lightColorScheme(
            primary = c.signal, onPrimary = c.onSignal, secondary = c.pack,
            background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, outline = c.line
        )
    }
    CompositionLocalProvider(LocalPelotonColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
