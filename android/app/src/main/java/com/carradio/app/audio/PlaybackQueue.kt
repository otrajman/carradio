package com.carradio.app.audio

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
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
    private val onEnded: (BurstPayload) -> Unit = {}
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

    init {
        scope.launch {
            for (item in channel) {
                if (!item.isBreadcrumb && burstAgeMs(item) > Constants.LIVE_BURST_MAX_AGE_MS) {
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
        channel.trySend(item)
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
            if (payload.isSystem || payload.audioPath == null) {
                val text = payload.text ?: return
                earcons.system() // triple-chime always precedes system playback
                tts.speak((item.speakPrefix ?: "") + text)
            } else {
                if (item.isBreadcrumb) earcons.breadcrumb() else earcons.incoming()
                playUrl(Constants.publicAudioUrl(payload.audioPath))
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

    private suspend fun playUrl(url: String) {
        withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        player.removeListener(this)
                        player.stop()
                        player.clearMediaItems()
                        if (cont.isActive) cont.resume(Unit)
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
    }

    private fun burstAgeMs(item: QueuedBurst): Long = try {
        System.currentTimeMillis() - Instant.parse(item.payload.createdAt).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis() - item.enqueuedAtMs
    }

    companion object {
        private const val TAG = "PlaybackQueue"
        private const val PLAYBACK_TIMEOUT_MS = 45_000L
    }
}
