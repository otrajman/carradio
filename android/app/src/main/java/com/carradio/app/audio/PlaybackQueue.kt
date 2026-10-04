package com.carradio.app.audio

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.carradio.app.Constants
import com.carradio.app.core.BurstPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

/**
 * PROTOCOL §5.6 FIFO play queue: never overlap two bursts; drop live bursts older than 60 s
 * at dequeue time (breadcrumbs are exempt). Earcon precedes each burst; system/text bursts
 * get the triple-chime + TTS. Audio focus (duck) is held only while sounding.
 */
class PlaybackQueue(
    private val scope: CoroutineScope, // must run on the main dispatcher (ExoPlayer thread)
    private val player: ExoPlayer,
    private val earcons: Earcons,
    private val focus: AudioFocusHelper,
    private val tts: TtsSpeaker,
    private val onStarted: (BurstPayload) -> Unit = {},
    private val onEnded: (BurstPayload) -> Unit = {},
    /**
     * Suspends until the speaker may sound. Car Radio plays immediately; PelotonCB's
     * half-duplex VOX (PROTOCOL §16) waits here for the rider's open snippet to finish.
     */
    private val awaitTurn: suspend () -> Unit = {},
    /**
     * When set, a burst's audio is downloaded here as soon as it is queued and played from
     * the file, so its turn starts without a network round trip (PelotonCB chunked phrases).
     */
    private val prefetchDir: File? = null,
    /**
     * PROTOCOL §16.4: a voice burst from the sender who finished playing less than this long
     * ago is the next chunk of the same phrase and plays without an earcon. 0 = always cue.
     */
    private val continuationGapMs: Long = 0
) {

    data class QueuedBurst(
        val payload: BurstPayload,
        val isBreadcrumb: Boolean = false,
        /** Spoken before system text, e.g. "Earlier here: " (PROTOCOL §6). */
        val speakPrefix: String? = null,
        val enqueuedAtMs: Long = System.currentTimeMillis()
    )

    private val channel = Channel<QueuedBurst>(Channel.UNLIMITED)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentPayload: BurstPayload? = null
        private set

    @Volatile
    var lastPlayed: BurstPayload? = null
        private set

    private var currentJob: Job? = null

    private val prefetched = HashMap<String, Deferred<File?>>() // main thread only
    private var lastVoiceTripId: String? = null
    private var lastVoiceEndedAtMs = 0L

    init {
        scope.launch {
            for (item in channel) {
                awaitTurn()
                if (!item.isBreadcrumb && burstAgeMs(item) > Constants.LIVE_BURST_MAX_AGE_MS) {
                    prefetched.remove(item.payload.messageId)?.let { discard(it) }
                    continue
                }
                val job = launch { playItem(item) }
                currentJob = job
                try {
                    job.join()
                } finally {
                    currentJob = null
                    currentPayload = null
                }
            }
        }
    }

    fun enqueue(item: QueuedBurst) {
        val path = item.payload.audioPath
        if (prefetchDir != null && !path.isNullOrEmpty()) {
            prefetched[item.payload.messageId] =
                scope.async(Dispatchers.IO) { download(Constants.publicAudioUrl(path), prefetchDir) }
        }
        channel.trySend(item)
    }

    private fun download(url: String, dir: File): File? {
        val file = File(dir, "rx_${UUID.randomUUID()}")
        return try {
            dir.mkdirs()
            URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        } catch (e: Exception) {
            Log.w(TAG, "prefetch failed", e)
            file.delete()
            null
        }
    }

    private fun discard(download: Deferred<File?>) {
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                download.await()?.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** The burst's audio: the prefetched file when it is ready in time, else the URL. */
    private suspend fun playVoice(payload: BurstPayload, path: String): Boolean {
        val download = prefetched.remove(payload.messageId)
            ?: return playUrl(Constants.publicAudioUrl(path))
        val local = withTimeoutOrNull(PREFETCH_WAIT_MS) { download.await() }
        return try {
            playUrl(local?.let { Uri.fromFile(it).toString() } ?: Constants.publicAudioUrl(path))
        } finally {
            discard(download)
        }
    }

    /** Stops the burst in flight. Returns its payload (for stealth-mute), or null if idle. */
    fun skipCurrent(): BurstPayload? {
        val payload = currentPayload
        currentJob?.cancel()
        return payload
    }

    /** "Hey Radio, repeat" — replay the last finished burst (age-exempt). */
    fun replayLast(): Boolean {
        val last = lastPlayed ?: return false
        enqueue(QueuedBurst(last, isBreadcrumb = true))
        return true
    }

    private suspend fun playItem(item: QueuedBurst) {
        val payload = item.payload
        currentPayload = payload
        onStarted(payload)
        focus.acquire()
        try {
            if (payload.isSystem) {
                earcons.system() // triple-chime always precedes system playback
                // §12: server-rendered AI voice when present; on-device TTS of the same text
                // if there is no audio or it fails to play.
                val played = payload.audioPath?.let { playVoice(payload, it) } ?: false
                if (!played) {
                    val text = payload.text ?: return
                    tts.speak((item.speakPrefix ?: "") + text)
                }
            } else if (payload.audioPath == null) {
                val text = payload.text ?: return
                earcons.system()
                tts.speak((item.speakPrefix ?: "") + text)
            } else {
                val continuation = !item.isBreadcrumb &&
                    payload.tripId == lastVoiceTripId &&
                    System.currentTimeMillis() - lastVoiceEndedAtMs <= continuationGapMs
                if (item.isBreadcrumb) earcons.breadcrumb() else if (!continuation) earcons.incoming()
                playVoice(payload, payload.audioPath)
                lastVoiceTripId = payload.tripId
                lastVoiceEndedAtMs = System.currentTimeMillis()
            }
            lastPlayed = payload
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "burst playback failed", e)
        } finally {
            focus.release()
            onEnded(payload)
        }
    }

    /** Plays one URL to the end. Returns false if it errored or timed out. */
    private suspend fun playUrl(url: String): Boolean {
        val ok = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_ENDED)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        player.removeListener(this)
                        player.stop()
                        player.clearMediaItems()
                        if (cont.isActive) cont.resume(false)
                    }
                }
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                cont.invokeOnCancellation {
                    mainHandler.post {
                        player.removeListener(listener)
                        player.stop()
                        player.clearMediaItems()
                    }
                }
            }
        }
        // Leave the player empty between bursts.
        player.clearMediaItems()
        return ok == true
    }

    private fun burstAgeMs(item: QueuedBurst): Long = try {
        System.currentTimeMillis() - Instant.parse(item.payload.createdAt).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis() - item.enqueuedAtMs
    }

    companion object {
        private const val TAG = "PlaybackQueue"
        private const val PLAYBACK_TIMEOUT_MS = 45_000L
        private const val PREFETCH_WAIT_MS = 4_000L
    }
}
