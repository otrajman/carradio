package com.carradio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PROTOCOL §13: high-contrast, dark mode ONLY — regardless of system theme.
val RadioBlack = Color(0xFF07070B)
val RadioSurface = Color(0xFF12121A)
val RadioGreen = Color(0xFF39E58C)
val RadioRed = Color(0xFFFF4D5E)
val RadioAmber = Color(0xFFFFC24D)
val RadioDim = Color(0xFF3A3A48)
val RadioText = Color(0xFFEDEDF2)
val RadioTextDim = Color(0xFF8A8A99)

private val DarkColors = darkColorScheme(
    primary = RadioGreen,
    onPrimary = RadioBlack,
    secondary = RadioAmber,
    onSecondary = RadioBlack,
    error = RadioRed,
    background = RadioBlack,
    onBackground = RadioText,
    surface = RadioSurface,
    onSurface = RadioText,
    surfaceVariant = RadioSurface,
    onSurfaceVariant = RadioTextDim,
    outline = RadioDim
)

@Composable
fun CarRadioTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() deliberately ignored: dark only while driving.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
