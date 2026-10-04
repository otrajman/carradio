package com.carradio.app.peloton

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.carradio.app.CarRadioApp
import com.carradio.app.Constants
import com.carradio.app.R
import com.carradio.app.audio.AudioFocusHelper
import com.carradio.app.audio.Earcons
import com.carradio.app.audio.PlaybackQueue
import com.carradio.app.audio.TtsSpeaker
import com.carradio.app.core.BurstFilter
import com.carradio.app.core.BurstPayload
import com.carradio.app.core.HandleGenerator
import com.carradio.app.core.PelotonGeo
import com.carradio.app.core.PelotonTag
import com.carradio.app.data.Repository
import com.carradio.app.data.SettingsStore
import com.carradio.app.data.SupabaseClientProvider
import com.carradio.app.service.H3Provider
import com.carradio.app.service.LocationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The PelotonCB ride: one foreground service (location + microphone + media playback) that
 * owns trip identity, GPS, pack channels, the receive filter, half-duplex playback, the VOX
 * mic, and the send pipeline. Reuses Car Radio's shared core/audio/data layers; only the
 * reach rules (PROTOCOL §16) and the hands-free transmit model differ.
 *
 * Earbud / handlebar remote: Play-Pause → pause/resume transmit, Next → skip + mute.
 */
class PelotonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsStore
    private lateinit var repository: Repository
    private lateinit var filter: BurstFilter
    private lateinit var earcons: Earcons
    private lateinit var focus: AudioFocusHelper
    private lateinit var tts: TtsSpeaker
    private lateinit var audioManager: AudioManager
    private var player: ExoPlayer? = null
    private var playbackQueue: PlaybackQueue? = null
    private var channels: PelotonChannels? = null
    private var vox: VoxRecorder? = null
    private var mediaSession: MediaSession? = null
    private var locationEngine: LocationEngine? = null

    private var tripId: String? = null
    private var handle: String = ""
    private var packTag: String = PelotonTag.OPEN_ROAD
    private var isPack = false
    private var lastFix: LocationEngine.Fix? = null
    private var started = false
    private var paused = false
    private var releaseTurnJob: Job? = null
    private var routedToHeadset = false
    @Volatile private var roadGuideEnabled = true
    private val syntheticCalledCells = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = (application as CarRadioApp).settings
        repository = Repository()
        filter = BurstFilter()
        earcons = Earcons()
        focus = AudioFocusHelper(this)
        tts = TtsSpeaker(this)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_SKIP_MUTE -> skipAndMute()
            ACTION_REPORT -> reportCurrentOrLast()
            ACTION_LEAVE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!started) {
                started = true
                val code = intent?.getStringExtra(EXTRA_PACK_CODE)
                val tag = PelotonTag.fromCode(code)
                isPack = tag != null
                packTag = tag ?: PelotonTag.OPEN_ROAD
                goForeground()
                boot(displayCode = if (isPack) code?.trim()?.uppercase() else null)
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
    }

    private fun boot(displayCode: String?) {
        PelotonState.reset()
        PelotonState.serviceRunning.value = true
        PelotonState.packCode.value = displayCode
        PelotonState.statusMessage.value = "Waiting for GPS…"

        routedToHeadset = routeToHeadsetMic()
        PelotonState.headsetMic.value = routedToHeadset

        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    // Over a Bluetooth headset the pack plays on the same voice link as the mic.
                    .setUsage(if (routedToHeadset) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false // AudioFocusHelper ducks music (PROTOCOL §10)
            )
        }
        player = exo

        vox = VoxRecorder(
            context = this,
            onSnippet = { file -> scope.launch { sendSnippet(file) } },
            onMic = { mic -> if (!paused) PelotonState.mic.value = mic },
            onLevel = { PelotonState.micLevel.value = it }
        )

        playbackQueue = PlaybackQueue(
            scope = scope,
            player = exo,
            earcons = earcons,
            focus = focus,
            tts = tts,
            onStarted = { payload ->
                PelotonState.isPlaying.value = true
                PelotonState.speakerHandle.value = payload.handle
            },
            onEnded = { payload ->
                PelotonState.isPlaying.value = false
                PelotonState.speakerHandle.value = null
                PelotonState.lastSpeakerHandle.value = payload.handle
                // Give the speaker's tail a moment to die out before reopening the mic;
                // the next queued burst cancels this and keeps the mic yielded.
                releaseTurnJob?.cancel()
                releaseTurnJob = scope.launch {
                    delay(TURN_RELEASE_DELAY_MS)
                    vox?.releaseTurn()
                }
            },
            awaitTurn = {
                releaseTurnJob?.cancel()
                vox?.yieldTurn()
            }
        )

        createMediaSession(exo)

        settings.roadGuideEnabled
            .onEach { roadGuideEnabled = it }
            .launchIn(scope)

        scope.launch {
            ensureTrip()
            channels = PelotonChannels(
                client = SupabaseClientProvider.client,
                scope = scope,
                packTag = if (isPack) packTag else null,
                selfTripId = { tripId },
                selfHandle = { handle },
                onBurst = { payload -> handleIncomingBurst(payload) },
                onRiderCountChanged = { refreshNotification() }
            )
            locationEngine = LocationEngine(this@PelotonService, scope, useFakeRoute = false) { fix ->
                onFix(fix)
            }.also { it.start() }
            startMic()
        }
    }

    /** PROTOCOL §1 identity, reused from Car Radio: one trip per ride within 4 h. */
    private suspend fun ensureTrip() {
        val stored = settings.loadTrip()
        val now = System.currentTimeMillis()
        if (stored != null && now - stored.createdAtMs < Constants.TRIP_IDLE_TIMEOUT_MS) {
            tripId = stored.tripId
            handle = stored.handle
            PelotonState.handle.value = stored.handle
            return
        }
        val newHandle = HandleGenerator.generate()
        while (scope.isActive && tripId == null) {
            try {
                val row = repository.createTrip(newHandle)
                tripId = row.id
                handle = row.phoneticHandle
                settings.saveTrip(SettingsStore.StoredTrip(row.id, row.phoneticHandle, now))
                PelotonState.handle.value = row.phoneticHandle
            } catch (e: Exception) {
                Log.w(TAG, "trip create failed; retrying", e)
                PelotonState.statusMessage.value = "Offline — retrying connection"
                delay(10_000)
            }
        }
    }

    // --- GPS -----------------------------------------------------------------------------

    private fun onFix(fix: LocationEngine.Fix) {
        val first = lastFix == null
        lastFix = fix
        if (first) {
            PelotonState.hasFix.value = true
            PelotonState.statusMessage.value = null
        }
        channels?.onLocation(fix)
        H3Provider.cellAddress(fix.lat, fix.lng, Constants.H3_RES_ROOMS)?.let { cell ->
            if (syntheticCalledCells.add(cell)) fetchSystemScripts(fix)
        }
    }

    /**
     * PROTOCOL §12 synthetic nodes (Gemini-voiced weather alerts + local trivia), once per
     * res-7 cell like Car Radio. Played to this rider only; never broadcast to the pack.
     */
    private fun fetchSystemScripts(fix: LocationEngine.Fix) {
        scope.launch {
            for (script in repository.fetchSyntheticScripts(fix.lat, fix.lng)) {
                playbackQueue?.enqueue(
                    PlaybackQueue.QueuedBurst(
                        payload = script.toPayload(fix.lat, fix.lng, "System", "synthetic-node"),
                        isBreadcrumb = true // age-exempt
                    )
                )
            }
        }
    }

    /** PROTOCOL §17: gated Road Guide answer to what this rider just said (private). */
    private fun askRoadGuide(sent: BurstPayload) {
        scope.launch {
            val answer = repository.askRoadGuide(sent) ?: return@launch
            val fix = lastFix ?: return@launch
            playbackQueue?.enqueue(
                PlaybackQueue.QueuedBurst(
                    payload = answer.toPayload(fix.lat, fix.lng, ROAD_GUIDE_HANDLE, ROAD_GUIDE_TRIP),
                    isBreadcrumb = true
                )
            )
        }
    }

    // --- Receive (PROTOCOL §5.1–5.3 + §16) ------------------------------------------------

    private fun handleIncomingBurst(payload: BurstPayload) {
        val fix = lastFix ?: return
        val trip = tripId ?: return
        val receiver = BurstFilter.ReceiverState(
            tripId = trip,
            lat = fix.lat,
            lng = fix.lng,
            heading = fix.headingDeg,
            speedMps = fix.speedMps,
            convoyTag = packTag
        )
        // Self / dedupe / mute / tag match are Car Radio's filter verbatim; a matching tag
        // plays regardless of geometry, which is exactly pack mode.
        if (filter.evaluate(payload, receiver) != BurstFilter.Decision.PLAY) return
        if (payload.isSystem) return
        if (!isPack &&
            !PelotonGeo.inRange(payload.lat, payload.lng, payload.heading, payload.speed, receiver)
        ) return
        playbackQueue?.enqueue(PlaybackQueue.QueuedBurst(payload))
    }

    // --- Transmit (VOX → PROTOCOL §4 pipeline) -------------------------------------------

    private fun startMic() {
        if (paused) return
        val ok = vox?.start() == true
        if (!ok) {
            PelotonState.mic.value = PelotonState.Mic.PAUSED
            PelotonState.statusMessage.value = "Microphone unavailable"
        }
    }

    fun togglePause() {
        if (!PelotonState.serviceRunning.value) return
        paused = !paused
        scope.launch {
            if (paused) {
                vox?.stop()
                PelotonState.mic.value = PelotonState.Mic.PAUSED
                earcons.muted()
            } else {
                earcons.micOpen() // cue plays before the mic opens, so it isn't transmitted
                startMic()
            }
            refreshNotification()
        }
    }

    private suspend fun sendSnippet(file: File) {
        val trip = tripId
        val fix = lastFix
        if (trip == null || fix == null) {
            file.delete()
            PelotonState.statusMessage.value = "Waiting for GPS — not sent"
            return
        }
        try {
            // §4.1 — shadowbanned riders "send" into the void.
            if (repository.isShadowbanned(trip)) {
                PelotonState.snippetsSent.value += 1
                return
            }
            val messageId = UUID.randomUUID().toString()
            val h3r9 = H3Provider.cellAddress(fix.lat, fix.lng, Constants.H3_RES_MESSAGE) ?: ""
            val audioPath = repository.uploadBurst(trip, messageId, file.readBytes(), extension = "m4a")
            val payload = BurstPayload(
                messageId = messageId,
                tripId = trip,
                handle = handle,
                kind = BurstPayload.KIND_VOICE,
                audioPath = audioPath,
                lat = fix.lat,
                lng = fix.lng,
                heading = fix.headingDeg,
                speed = fix.speedMps,
                h3R9 = h3r9,
                createdAt = Instant.now().toString(),
                convoy = packTag
            )
            repository.insertMessage(payload)
            channels?.broadcast(payload)
            // No "sent" earcon: in VOX the rider may already be talking again, and the cue
            // would land in their next snippet. The UI counter confirms instead.
            PelotonState.snippetsSent.value += 1
            PelotonState.statusMessage.value = null
            if (roadGuideEnabled) askRoadGuide(payload)
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            PelotonState.statusMessage.value = "Send failed — check connection"
        } finally {
            file.delete()
        }
    }

    // --- Mute / report (PROTOCOL §8, unchanged) -------------------------------------------

    fun skipAndMute() {
        scope.launch {
            val queue = playbackQueue ?: return@launch
            val target = queue.skipCurrent() ?: queue.lastPlayed ?: return@launch
            val muter = tripId ?: return@launch
            if (target.tripId == muter) return@launch
            if (target.isSystem) {
                earcons.muted() // skipped; system voices are never muted/reported
                return@launch
            }
            filter.mute(target.tripId)
            earcons.muted()
            repository.insertMuteEvent(muter, target.tripId)
        }
    }

    fun reportCurrentOrLast() {
        scope.launch {
            val queue = playbackQueue ?: return@launch
            val reporter = tripId ?: return@launch
            val target = queue.skipCurrent() ?: queue.lastPlayed ?: return@launch
            if (target.tripId == reporter || target.isSystem) return@launch
            filter.mute(target.tripId)
            earcons.muted()
            repository.insertReport(reporter, target.tripId, target.messageId)
        }
    }

    // --- Bluetooth headset mic -----------------------------------------------------------

    /**
     * Cyclists ride with earbuds: use the headset mic when one is connected (API 31+
     * communication-device routing). Falls back to the phone mic otherwise.
     */
    private fun routeToHeadsetMic(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setCommunicationDevice(device)
        } catch (e: Exception) {
            Log.w(TAG, "headset routing failed", e)
            false
        }
    }

    private fun clearHeadsetRoute() {
        if (!routedToHeadset || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    // --- MediaSession: earbud / remote buttons (PROTOCOL §10 mapping, VOX semantics) ------

    private fun createMediaSession(exo: ExoPlayer) {
        val forwarding = object : ForwardingPlayer(exo) {
            override fun play() = togglePause()
            override fun pause() = togglePause()
            override fun seekToNext() = skipAndMute()
            override fun seekToNextMediaItem() = skipAndMute()

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                getAvailableCommands().contains(command)
        }
        mediaSession = MediaSession.Builder(this, forwarding)
            .setId("pelotoncb_live")
            .build()
    }

    // --- Notification --------------------------------------------------------------------

    private fun refreshNotification() {
        if (!started) return
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PelotonActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode,
            Intent(this, PelotonService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val riders = PelotonState.riderCount.value
        val where = PelotonState.packCode.value?.let { "Pack $it" } ?: "Open road"
        return NotificationCompat.Builder(this, CarRadioApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(
                if (paused) getString(R.string.notification_title_paused)
                else getString(R.string.notification_title)
            )
            .setContentText("$where · $riders riding with you")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                getString(if (paused) R.string.notification_resume else R.string.notification_pause),
                action(ACTION_TOGGLE_PAUSE, 1)
            )
            .addAction(0, getString(R.string.notification_skip), action(ACTION_SKIP_MUTE, 2))
            .addAction(0, getString(R.string.notification_leave), action(ACTION_LEAVE, 3))
            .build()
    }

    // --- Teardown ------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDestroy() {
        vox?.stop()
        locationEngine?.stop()
        val ch = channels
        GlobalScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { ch?.shutdown() }
        }
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        tts.shutdown()
        clearHeadsetRoute()
        PelotonState.reset()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PelotonService"
        private const val NOTIFICATION_ID = 42
        private const val TURN_RELEASE_DELAY_MS = 350L
        private const val ROAD_GUIDE_HANDLE = "Road Guide"
        private const val ROAD_GUIDE_TRIP = "road-guide"

        const val EXTRA_PACK_CODE = "com.pelotoncb.extra.PACK_CODE"
        const val ACTION_TOGGLE_PAUSE = "com.pelotoncb.action.TOGGLE_PAUSE"
        const val ACTION_SKIP_MUTE = "com.pelotoncb.action.SKIP_MUTE"
        const val ACTION_REPORT = "com.pelotoncb.action.REPORT"
        const val ACTION_LEAVE = "com.pelotoncb.action.LEAVE"

        /** @param packCode null / blank → open-road mode. */
        fun start(context: Context, packCode: String?) {
            context.startForegroundService(
                Intent(context, PelotonService::class.java)
                    .putExtra(EXTRA_PACK_CODE, packCode)
            )
        }

        fun sendAction(context: Context, action: String) {
            context.startService(Intent(context, PelotonService::class.java).setAction(action))
        }
    }
}
