package com.carradio.app.peloton.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carradio.app.core.PelotonTag
import com.carradio.app.core.RiderName

/**
 * Pre-ride screen: join a pack by code, mint a new code to share, or ride open road.
 * Everything is sized for gloves and glare: 64 dp+ targets, heavy type, no small print
 * that matters.
 */
@Composable
fun JoinScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    onJoinPack: () -> Unit,
    onNewPack: () -> Unit,
    onRideOpen: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    roadGuide: Boolean,
    onRoadGuideChange: (Boolean) -> Unit
) {
    val c = Peloton.colors
    val valid = PelotonTag.fromCode(code) != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Wordmark()
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Hands-free radio for your group ride. Talk and the pack hears you.",
            fontSize = 16.sp,
            color = c.muted,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(28.dp))
        Eyebrow("Your name · optional")
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = name,
            onValueChange = { onNameChange(it.take(RiderName.MAX_LENGTH)) },
            singleLine = true,
            textStyle = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = c.ink,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrect = false,
                imeAction = ImeAction.Done
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surface)
                        .border(2.dp, c.line, RoundedCornerShape(14.dp))
                        .padding(vertical = 16.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = "Leave blank for a random one",
                            fontSize = 16.sp,
                            color = c.muted
                        )
                    }
                    inner()
                }
            }
        )

        Spacer(Modifier.height(24.dp))
        Eyebrow("Join a pack")
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = code,
            onValueChange = { onCodeChange(it.uppercase().take(24)) },
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
                letterSpacing = 3.sp,
                color = c.ink,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrect = false,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { if (valid) onJoinPack() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surface)
                        .border(2.dp, if (valid) c.ink else c.line, RoundedCornerShape(14.dp))
                        .padding(vertical = 18.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (code.isEmpty()) {
                        Text(
                            text = "PACK CODE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 34.sp,
                            letterSpacing = 3.sp,
                            color = c.line
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(
                label = "JOIN PACK",
                background = if (valid) c.signal else c.line,
                content = if (valid) c.onSignal else c.muted,
                enabled = valid,
                onClick = onJoinPack,
                modifier = Modifier.weight(1.4f)
            )
            BigButton(
                label = "NEW CODE",
                background = c.surface,
                content = c.ink,
                border = c.ink,
                onClick = onNewPack,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "Everyone who enters the same code hears each other — anywhere, any distance.",
            fontSize = 13.sp,
            color = c.muted,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(2.dp).background(c.line))
            Text("  OR  ", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.muted)
            Box(Modifier.weight(1f).height(2.dp).background(c.line))
        }
        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.ink)
                .clickable(onClick = onRideOpen)
                .padding(horizontal = 22.dp, vertical = 22.dp)
        ) {
            Text(
                text = "RIDE OPEN ROAD",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = c.background
            )
            Text(
                text = "No code. Hear any PelotonCB rider within 500 m heading your way.",
                fontSize = 14.sp,
                color = c.background.copy(alpha = 0.7f),
                lineHeight = 19.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, c.line, RoundedCornerShape(14.dp))
                .clickable { onRoadGuideChange(!roadGuide) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ROAD GUIDE AI", fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.ink)
                Text(
                    text = "Ask about the route, the view, or places nearby — it answers only you, " +
                        "and ignores everything else.",
                    fontSize = 13.sp,
                    color = c.muted,
                    lineHeight = 17.sp
                )
            }
            Switch(
                checked = roadGuide,
                onCheckedChange = onRoadGuideChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.pack,
                    checkedThumbColor = c.onPack
                )
            )
        }

        Spacer(Modifier.height(36.dp))
        Text(
            text = "Voice kept 24 h · no accounts",
            fontSize = 12.sp,
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Wordmark() {
    val c = Peloton.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "PELOTON",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
            color = c.ink
        )
        Spacer(Modifier.padding(start = 8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.signal)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Text("CB", fontSize = 30.sp, fontWeight = FontWeight.Black, color = c.onSignal)
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = Peloton.colors.muted) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.5.sp,
        color = color
    )
}

@Composable
fun BigButton(
    label: String,
    background: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: Color? = null,
    height: Int = 64,
    fontSize: Int = 18
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(height.dp)
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            color = content,
            textAlign = TextAlign.Center
        )
    }
}
