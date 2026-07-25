package com.carradio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carradio.app.CarRadioApp
import com.carradio.app.Constants
import com.carradio.app.service.RadioState
import com.carradio.app.ui.theme.RadioDim
import com.carradio.app.ui.theme.RadioGreen
import com.carradio.app.ui.theme.RadioTextDim
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as CarRadioApp).settings
    val scope = rememberCoroutineScope()

    val wakeWord by settings.wakeWordEnabled.collectAsStateWithLifecycle(initialValue = true)
    val synthetic by settings.syntheticNodesEnabled.collectAsStateWithLifecycle(initialValue = true)
    val fakeGps by settings.fakeGpsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val convoyCode by settings.convoyCode.collectAsStateWithLifecycle(initialValue = "")
    val handle by RadioState.handle.collectAsStateWithLifecycle()
    val serviceRunning by RadioState.serviceRunning.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = "‹ Back",
                fontSize = 16.sp,
                color = RadioGreen,
                modifier = Modifier.clickable { onBack() }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))

            SettingToggle(
                title = "\"Hey Radio\" wake word",
                subtitle = "Hands-free: \"Hey Radio\" then talk, or say \"mute\" / \"repeat\". " +
                    "Uses the on-device speech recognizer when available.",
                checked = wakeWord,
                onChange = { scope.launch { settings.setWakeWordEnabled(it) } }
            )
            SettingToggle(
                title = "Synthetic nodes",
                subtitle = "System voice with local alerts and trivia on quiet roads " +
                    "(triple-chime, clearly non-human).",
                checked = synthetic,
                onChange = { scope.launch { settings.setSyntheticNodesEnabled(it) } }
            )
            SettingToggle(
                title = "Fake GPS demo route",
                subtitle = "Replays a bundled US-101 route at ~55 mph for indoor testing. " +
                    "Takes effect the next time you go on air.",
                checked = fakeGps,
                onChange = { scope.launch { settings.setFakeGpsEnabled(it) } }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Convoy code",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = RadioTextDim
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = convoyCode,
                onValueChange = { scope.launch { settings.setConvoyCode(it) } },
                placeholder = { Text("optional — friends only", color = RadioDim) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "With a code set, you talk and listen only to drivers using the same " +
                    "code. Leave empty for public traffic. Takes effect next time you go on air.",
                fontSize = 12.sp,
                color = RadioDim
            )

            Spacer(Modifier.height(28.dp))
            Text(
                text = "This trip",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = RadioTextDim
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (serviceRunning) "On air as \"$handle\"" else "Off air",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Handles are ephemeral and regenerate every trip. No accounts, no names, " +
                    "no maps. Messages self-delete after 24 hours.\n\nBackend: " +
                    Constants.SUPABASE_URL,
                fontSize = 13.sp,
                color = RadioTextDim,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = RadioTextDim,
                lineHeight = 17.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = RadioGreen)
        )
    }
}
