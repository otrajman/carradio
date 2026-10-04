package com.carradio.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.carradio.app.MainActivity
import com.carradio.app.R
import com.carradio.app.audio.AudioFocusHelper
import com.carradio.app.audio.BurstRecorder
import com.carradio.app.audio.Earcons
import com.carradio.app.audio.PlaybackQueue
import com.carradio.app.audio.TtsSpeaker
import com.carradio.app.core.BurstFilter
import com.carradio.app.core.BurstPayload
import com.carradio.app.core.ElasticModeTracker
import com.carradio.app.core.HandleGenerator
import com.carradio.app.data.Repository
import com.carradio.app.core.ConvoyTag
import com.carradio.app.data.SettingsStore
import com.carradio.app.data.SupabaseClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.UUID
import android.app.Service

/**
 * The one foreground service (location + microphone + media playback) that owns the whole
 * live session: GPS, H3 rooms, realtime pub/sub, presence, recording, the play queue,
 * breadcrumbs, elastic mode, wake word, and the steering-wheel MediaSession mapping
 * (Play/Pause → push-to-talk toggle, Next → skip + stealth-mute; PROTOCOL §8/§10).
 */
class RadioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsStore
    private lateinit var repository: Repository
    private lateinit var filter: BurstFilter
    private lateinit var elasticTracker: ElasticModeTracker
    private lateinit var earcons: Earcons
    private lateinit var focus: AudioFocusHelper
    private lateinit var recorder: BurstRecorder
    private lateinit var tts: TtsSpeaker
    private lateinit var player: ExoPlayer
    private lateinit var playbackQueue: PlaybackQueue
    private lateinit var roomManager: RoomManager
    private lateinit var breadcrumbs: BreadcrumbManager
    private lateinit var wakeWord: WakeWordManager
    /** §14: hashed convoy tag for this trip; null = public mode. */
    private var convoyTag: String? = null
    private var mediaSession: MediaSession? = null
    private var locationEngine: LocationEngine? = null

    private var tripId: String? = null
    private var handle: String = ""
    private var lastFix: LocationEngine.Fix? = null
    private var started = false

    // feature flags (mirrored from DataStore)
    @Volatile private var wakeWordEnabled = true
    @Volatile private var syntheticEnabled = true
    @Volatile private var roadGuideEnabled = true

    private val syntheticCalledCells = mutableSetOf<String>()

    // Drive Mode lock bookkeeping (PROTOCOL §13)
    private var fastSinceMs: Long? = null
    private var slowSinceMs: Long? = null
    private var passengerOverride = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = (application as CarRadioApp).settings
        repository = Repository()
        filter = BurstFilter()
        elasticTracker = ElasticModeTracker()
        earcons = Earcons()
        focus = AudioFocusHelper(this)
        recorder = BurstRecorder(this, scope)
        tts = TtsSpeaker(this)

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false // we hold TRANSIENT_MAY_DUCK ourselves (§10)
            )
        }

        playbackQueue = PlaybackQueue(
            scope = scope,
            player = player,
            earcons = earcons,
            focus = focus,
            tts = tts,
            onStarted = { payload ->
                RadioState.isPlaying.value = true
                RadioState.lastSpeakerHandle.value = payload.handle.ifBlank { "System" }
                RadioState.lastSpeakerTripId.value = payload.tripId
                wakeWord.pause()
            },
            onEnded = {
                RadioState.isPlaying.value = false
                if (!recorder.isRecording) wakeWord.resume()
            }
        )

        roomManager = RoomManager(
            client = SupabaseClientProvider.client,
            scope = scope,
            selfTripId = { tripId },
            selfHandle = { handle },
            onBurst = { payload -> scope.launch { handleIncomingBurst(payload) } },
            onPeerCountChanged = { count ->
                RadioState.peerCount.value = count
                elasticTracker.onPresence(count, System.currentTimeMillis())
            },
            onRes7CellEntered = { cell, fix -> onRes7CellEntered(cell, fix) }
        )

        breadcrumbs = BreadcrumbManager(
            scope = scope,
            repository = repository,
            settings = settings,
            filter = filter,
            queue = playbackQueue,
            selfTripId = { tripId },
            receiverState = { currentReceiverState() }
        )

        wakeWord = WakeWordManager(
            context = this,
            onMute = { scope.launch { muteTrip(RadioState.lastSpeakerTripId.value) } },
            onRepeat = { playbackQueue.replayLast() },
            onTalk = { if (!recorder.isRecording) toggleTalk(autoStop = true) },
            onReport = { reportCurrentOrLast() }
        )

        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TALK -> toggleTalk(autoStop = false)
            ACTION_SKIP_MUTE -> skipAndMute()
            ACTION_REPORT -> reportCurrentOrLast()
            ACTION_PASSENGER_OVERRIDE -> {
                passengerOverride = true
                RadioState.driveLocked.value = false
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!started) {
                    started = true
                    goForeground()
                    boot()
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
    }

    private fun boot() {
        RadioState.reset()
        RadioState.serviceRunning.value = true

        // Mirror feature flags.
        settings.wakeWordEnabled
            .onEach { enabled ->
                wakeWordEnabled = enabled
                if (enabled && wakeWord.isAvailable) {
                    wakeWord.start()
                    RadioState.wakeWordActive.value = true
                } else {
                    wakeWord.stop()
                    RadioState.wakeWordActive.value = false
                }
            }
            .launchIn(scope)
        settings.syntheticNodesEnabled
            .onEach { syntheticEnabled = it }
            .launchIn(scope)
        settings.roadGuideEnabled
            .onEach { roadGuideEnabled = it }
            .launchIn(scope)

        scope.launch {
            convoyTag = ConvoyTag.fromCode(settings.convoyCode.first())
            ensureTrip()
            val fake = settings.fakeGpsEnabled.first()
            locationEngine = LocationEngine(this@RadioService, scope, fake) { fix -> onFix(fix) }
                .also { it.start() }
        }

        // Elastic-mode / breadcrumb tick loop.
        scope.launch {
            while (isActive) {
                delay(5_000)
                RadioState.elasticMode.value = elasticTracker.tick(System.currentTimeMillis())
                breadcrumbs.tick()
            }
        }
    }

    /** PROTOCOL §1: new trip per launch, reused only within the 4 h idle window. */
    private suspend fun ensureTrip() {
        val stored = settings.loadTrip()
        val now = System.currentTimeMillis()
        if (stored != null && now - stored.createdAtMs < Constants.TRIP_IDLE_TIMEOUT_MS) {
            tripId = stored.tripId
            handle = stored.handle
            RadioState.tripId.value = stored.tripId
            RadioState.handle.value = stored.handle
            return
        }
        val newHandle = HandleGenerator.generate()
        while (scope.isActive && tripId == null) {
            try {
                val row = repository.createTrip(newHandle)
                tripId = row.id
                handle = row.phoneticHandle
                settings.saveTrip(SettingsStore.StoredTrip(row.id, row.phoneticHandle, now))
                RadioState.tripId.value = row.id
                RadioState.handle.value = row.phoneticHandle
                RadioState.statusMessage.value = null
            } catch (e: Exception) {
                Log.w(TAG, "trip create failed; retrying", e)
                RadioState.statusMessage.value = "Offline — retrying connection"
                delay(10_000)
            }
        }
    }

    // --- GPS ---------------------------------------------------------------------------

    private fun onFix(fix: LocationEngine.Fix) {
        lastFix = fix
        RadioState.speedMps.value = fix.speedMps
        RadioState.headingDeg.value = fix.headingDeg
        updateDriveLock(fix.speedMps)
        if (!H3Provider.isAvailable) {
            RadioState.statusMessage.value = "H3 unavailable on this device — rooms disabled"
            return
        }
        roomManager.onLocation(fix)
        breadcrumbs.onFix(fix)
    }

    private fun updateDriveLock(speedMps: Double) {
        val now = System.currentTimeMillis()
        when {
            speedMps > Constants.DRIVE_LOCK_SPEED_MPS -> {
                slowSinceMs = null
                val since = fastSinceMs ?: now.also { fastSinceMs = it }
                if (now - since >= Constants.DRIVE_LOCK_AFTER_MS && !passengerOverride) {
                    RadioState.driveLocked.value = true
                }
            }
            speedMps < Constants.DRIVE_UNLOCK_SPEED_MPS -> {
                fastSinceMs = null
                val since = slowSinceMs ?: now.also { slowSinceMs = it }
                if (now - since >= Constants.DRIVE_UNLOCK_AFTER_MS) {
                    RadioState.driveLocked.value = false
                    passengerOverride = false
                }
            }
            else -> {
                fastSinceMs = null
                slowSinceMs = null
            }
        }
    }

    private fun currentReceiverState(): BurstFilter.ReceiverState? {
        val fix = lastFix ?: return null
        val trip = tripId ?: return null
        return BurstFilter.ReceiverState(
            tripId = trip,
            lat = fix.lat,
            lng = fix.lng,
            heading = fix.headingDeg,
            speedMps = fix.speedMps,
            convoyTag = convoyTag
        )
    }

    // --- Receive path (PROTOCOL §5/§7) --------------------------------------------------

    private fun handleIncomingBurst(payload: BurstPayload) {
        val receiver = currentReceiverState() ?: return
        val elasticNow = elasticTracker.elastic
        val decision = filter.evaluate(payload, receiver, elasticMode = elasticNow)
        if (decision != BurstFilter.Decision.PLAY) return
        if (!elasticNow || filter.passesStrictGeometry(payload, receiver)) {
            elasticTracker.onStrictBurstPassed(System.currentTimeMillis())
        }
        playbackQueue.enqueue(PlaybackQueue.QueuedBurst(payload))
    }

    // --- Send path (PROTOCOL §4) --------------------------------------------------------

    fun toggleTalk(autoStop: Boolean) {
        if (recorder.isRecording) {
            recorder.stop()
            return
        }
        scope.launch {
            if (tripId == null || lastFix == null) return@launch
            wakeWord.pause()
            focus.acquire()
            RadioState.isRecording.value = true
            earcons.micOpen()
            val startedOk = recorder.start(autoStopOnSilence = autoStop) { file ->
                scope.launch { onRecordingFinished(file) }
            }
            if (!startedOk) {
                RadioState.isRecording.value = false
                focus.release()
                wakeWord.resume()
            }
        }
    }

    private suspend fun onRecordingFinished(file: File?) {
        RadioState.isRecording.value = false
        focus.release()
        if (!RadioState.isPlaying.value) wakeWord.resume()
        if (file != null) sendBurst(file)
    }

    private suspend fun sendBurst(file: File) {
        val trip = tripId ?: return
        val fix = lastFix ?: return
        try {
            // §4.1 — shadowban check first; if banned, pretend to send.
            if (repository.isShadowbanned(trip)) {
                earcons.sent()
                return
            }
            val messageId = UUID.randomUUID().toString()
            val h3r9 = H3Provider.cellAddress(fix.lat, fix.lng, Constants.H3_RES_MESSAGE) ?: ""

            // §4.2 — upload <trip_id>/<message_id>.ogg
            val audioPath = repository.uploadBurst(trip, messageId, file.readBytes())

            val payload = BurstPayload(
                messageId = messageId,
                tripId = trip,
                handle = handle,
                kind = BurstPayload.KIND_VOICE,
                audioPath = audioPath,
                text = null,
                lat = fix.lat,
                lng = fix.lng,
                heading = fix.headingDeg,
                speed = fix.speedMps,
                h3R9 = h3r9,
                createdAt = Instant.now().toString(),
                convoy = convoyTag
            )

            // §4.3 — breadcrumb insert.
            repository.insertMessage(payload)

            // §4.4 — broadcast to the publish set.
            roomManager.broadcastBurst(payload)

            earcons.sent()
            if (roadGuideEnabled) askRoadGuide(payload)
            RadioState.statusMessage.value = null
        } catch (e: Exception) {
            Log.e(TAG, "send pipeline failed", e)
            RadioState.statusMessage.value = "Send failed — check connection"
        } finally {
            file.delete()
        }
    }

    /**
     * PROTOCOL §17: the Road Guide hears what this driver just said and, only if it passes
     * the server's verification gate (route / scenery / POI talk), answers privately.
     */
    private fun askRoadGuide(sent: BurstPayload) {
        scope.launch {
            val answer = repository.askRoadGuide(sent) ?: return@launch
            val fix = lastFix ?: return@launch
            playbackQueue.enqueue(
                PlaybackQueue.QueuedBurst(
                    payload = answer.toPayload(fix.lat, fix.lng, ROAD_GUIDE_HANDLE, ROAD_GUIDE_TRIP),
                    isBreadcrumb = true // age-exempt: the answer is for this driver
                )
            )
        }
    }

    // --- Mute / skip (PROTOCOL §8) ------------------------------------------------------

    fun skipAndMute() {
        scope.launch {
            val current = playbackQueue.skipCurrent()
            val target = current ?: playbackQueue.lastPlayed
            muteTrip(target?.tripId)
        }
    }

    /**
     * PROTOCOL §8 report: reports the playing (or last played) burst, stops it, and
     * mutes the sender locally. The sender is never notified.
     */
    fun reportCurrentOrLast() {
        scope.launch {
            val reporter = tripId ?: return@launch
            val target = playbackQueue.skipCurrent() ?: playbackQueue.lastPlayed ?: return@launch
            if (target.tripId == reporter) return@launch
            filter.mute(target.tripId)
            earcons.muted()
            repository.insertReport(
                reporterTripId = reporter,
                reportedTripId = target.tripId,
                messageId = target.messageId
            )
        }
    }

    private suspend fun muteTrip(targetTripId: String?) {
        val muter = tripId ?: return
        if (targetTripId == null || targetTripId == muter) return
        filter.mute(targetTripId)
        earcons.muted()
        // Sender is NEVER notified; this insert only feeds the server-side shadowban trigger.
        repository.insertMuteEvent(muter, targetTripId)
    }

    // --- Synthetic nodes (PROTOCOL §12) -------------------------------------------------

    private fun onRes7CellEntered(cell: String, fix: LocationEngine.Fix) {
        if (!syntheticEnabled) return
        if (!syntheticCalledCells.add(cell)) return // at most once per res-7 cell per session
        scope.launch {
            val scripts = repository.fetchSyntheticScripts(fix.lat, fix.lng)
            for (script in scripts) {
                playbackQueue.enqueue(
                    PlaybackQueue.QueuedBurst(
                        payload = script.toPayload(fix.lat, fix.lng, "System", "synthetic-node"),
                        isBreadcrumb = true // age-exempt
                    )
                )
            }
        }
    }

    // --- MediaSession: steering wheel / headset buttons (PROTOCOL §10) ------------------

    private fun createMediaSession() {
        val forwarding = object : ForwardingPlayer(player) {
            override fun play() {
                toggleTalk(autoStop = false)
            }

            override fun pause() {
                toggleTalk(autoStop = false)
            }

            override fun seekToNext() {
                skipAndMute()
            }

            override fun seekToNextMediaItem() {
                skipAndMute()
            }

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
            .setId("carradio_live")
            .build()
    }

    // --- Notification -------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode,
            Intent(this, RadioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CarRadioApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(handle.ifBlank { getString(R.string.app_name) })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_talk), action(ACTION_TOGGLE_TALK, 1))
            .addAction(0, getString(R.string.notification_skip), action(ACTION_SKIP_MUTE, 2))
            .addAction(0, getString(R.string.notification_stop), action(ACTION_STOP, 3))
            .build()
    }

    // --- Teardown -----------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDestroy() {
        recorder.cancel()
        wakeWord.stop()
        locationEngine?.stop()
        // Detached cleanup: the realtime client is process-wide, so channels must be removed
        // even after this service's scope dies — without blocking the main thread here.
        val rm = roomManager
        GlobalScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { rm.shutdown() }
        }
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        tts.shutdown()
        RadioState.reset()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RadioService"
        private const val NOTIFICATION_ID = 41
        private const val ROAD_GUIDE_HANDLE = "Road Guide"
        private const val ROAD_GUIDE_TRIP = "road-guide"

        const val ACTION_TOGGLE_TALK = "com.carradio.app.action.TOGGLE_TALK"
        const val ACTION_SKIP_MUTE = "com.carradio.app.action.SKIP_MUTE"
        const val ACTION_REPORT = "com.carradio.app.action.REPORT"
        const val ACTION_PASSENGER_OVERRIDE = "com.carradio.app.action.PASSENGER_OVERRIDE"
        const val ACTION_STOP = "com.carradio.app.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RadioService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RadioService::class.java).setAction(ACTION_STOP)
            )
        }

        fun sendAction(context: Context, action: String) {
            context.startService(
                Intent(context, RadioService::class.java).setAction(action)
            )
        }
    }
}
