package com.carradio.app.peloton

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.carradio.app.CarRadioApp
import com.carradio.app.core.PelotonTag
import com.carradio.app.peloton.ui.BigButton
import com.carradio.app.peloton.ui.JoinScreen
import com.carradio.app.peloton.ui.Peloton
import com.carradio.app.peloton.ui.PelotonTheme
import com.carradio.app.peloton.ui.RideScreen

class PelotonActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PelotonTheme { PelotonRoot() } }
    }
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

/** Mic + GPS are required; Bluetooth (headset mic) and notifications are nice-to-have. */
private val OPTIONAL = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PelotonRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("peloton", Context.MODE_PRIVATE) }

    var permissionsGranted by remember {
        mutableStateOf(requiredPermissions().filter { it !in OPTIONAL }.all { granted(context, it) })
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = requiredPermissions().filter { it !in OPTIONAL }
            .all { results[it] == true || granted(context, it) }
    }

    val running by PelotonState.serviceRunning.collectAsStateWithLifecycle()
    val settings = remember { (context.applicationContext as CarRadioApp).settings }
    val roadGuide by settings.roadGuideEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf(prefs.getString(KEY_LAST_CODE, "") ?: "") }

    // Handlebar mount: the screen stays on for the whole ride.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun share(packCode: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Ride with us on PelotonCB — pack code $packCode")
        }
        context.startActivity(Intent.createChooser(send, "Share pack code"))
    }

    when {
        !permissionsGranted -> PermissionGate { launcher.launch(requiredPermissions().toTypedArray()) }

        running -> RideScreen(
            onTogglePause = { PelotonService.sendAction(context, PelotonService.ACTION_TOGGLE_PAUSE) },
            onSkipMute = { PelotonService.sendAction(context, PelotonService.ACTION_SKIP_MUTE) },
            onReport = { PelotonService.sendAction(context, PelotonService.ACTION_REPORT) },
            onShareCode = { PelotonState.packCode.value?.let(::share) },
            onLeave = { PelotonService.sendAction(context, PelotonService.ACTION_LEAVE) }
        )

        else -> JoinScreen(
            code = code,
            onCodeChange = { code = it },
            onJoinPack = {
                if (PelotonTag.fromCode(code) != null) {
                    prefs.edit().putString(KEY_LAST_CODE, code).apply()
                    PelotonService.start(context, code)
                }
            },
            onNewPack = {
                code = PelotonTag.generateCode()
                share(code)
            },
            onRideOpen = { PelotonService.start(context, null) },
            roadGuide = roadGuide,
            onRoadGuideChange = { scope.launch { settings.setRoadGuideEnabled(it) } }
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    val c = Peloton.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("PELOTON CB", fontSize = 40.sp, fontWeight = FontWeight.Black, color = c.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "PelotonCB needs the microphone (it transmits when you talk, until you pause " +
                "it) and your location (to place you with nearby riders). Bluetooth lets it use " +
                "your earbuds' mic. Voice is deleted after 24 hours.",
            fontSize = 16.sp,
            color = c.muted,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(28.dp))
        BigButton(
            label = "ALLOW",
            background = c.signal,
            content = c.onSignal,
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val KEY_LAST_CODE = "last_pack_code"
