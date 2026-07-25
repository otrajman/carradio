package com.carradio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carradio.app.service.RadioService
import com.carradio.app.service.RadioState
import com.carradio.app.ui.DriveModeScreen
import com.carradio.app.ui.SettingsScreen
import com.carradio.app.ui.theme.CarRadioTheme
import com.carradio.app.ui.theme.RadioTextDim

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CarRadioTheme {
                AppRoot()
            }
        }
    }
}

private fun requiredPermissions(): List<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = requiredPermissions()
            // POST_NOTIFICATIONS denial should not block a car app; mic + GPS must be granted.
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { results[it] == true || alreadyGranted(context, it) }
    }

    val locked by RadioState.driveLocked.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    when {
        !permissionsGranted -> PermissionGate(
            onRequest = { launcher.launch(requiredPermissions().toTypedArray()) }
        )

        showSettings && !locked -> SettingsScreen(onBack = { showSettings = false })

        else -> DriveModeScreen(
            onToggleTalk = {
                RadioService.sendAction(context, RadioService.ACTION_TOGGLE_TALK)
            },
            onSkipMute = {
                RadioService.sendAction(context, RadioService.ACTION_SKIP_MUTE)
            },
            onReport = {
                RadioService.sendAction(context, RadioService.ACTION_REPORT)
            },
            onGoOnAir = { RadioService.start(context) },
            onGoOffAir = { RadioService.stop(context) },
            onExitApp = {
                // Full exit: stop the foreground service, then remove the task.
                RadioService.stop(context)
                (context as? ComponentActivity)?.finishAndRemoveTask()
            },
            onOpenSettings = { showSettings = true },
            onPassengerOverride = {
                RadioService.sendAction(context, RadioService.ACTION_PASSENGER_OVERRIDE)
            }
        )
    }
}

private fun alreadyGranted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Car Radio",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Talk to the drivers in your traffic. Ephemeral, hands-free, no accounts." +
                    "\n\nCar Radio needs your location (to find your traffic) and the microphone " +
                    "(to send voice bursts). Nothing is stored longer than 24 hours.",
                fontSize = 15.sp,
                color = RadioTextDim,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Grant permissions", fontSize = 17.sp)
            }
        }
    }
}
