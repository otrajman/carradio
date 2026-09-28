package com.carradio.app.peloton

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carradio.app.Constants
import com.carradio.app.core.VoxDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * PROTOCOL §16 voice-activated transmit. While running, the mic is open and every spoken
 * phrase becomes one snippet (≤ 10 s) delivered to [onSnippet] — no button presses.
 *
 * - `AudioRecord` with the VOICE_COMMUNICATION source: the platform's echo canceller and
 *   noise suppressor are exactly what wind + an open speaker on the bars need.
 * - Each 20 ms frame's level feeds the shared [VoxDetector]; a 400 ms pre-roll ring means
 *   the first syllable (spoken during the detector's attack window) is kept.
 * - Trailing silence is held back and only ~250 ms of it is written, so snippets end tight.
 * - Half-duplex: [yieldTurn] lets the current phrase finish, then holds the mic while the
 *   pack is playing; [releaseTurn] opens it again. The rider never transmits a teammate.
 *
 * All audio work happens on one dedicated thread; callbacks are posted to the main thread.
 */
class VoxRecorder(
    private val context: Context,
    private val onSnippet: (File) -> Unit,
    private val onMic: (PelotonState.Mic) -> Unit,
    private val onLevel: (Float) -> Unit
) {

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile private var held = false
    @Volatile private var turnRequested = false
    @Volatile private var turnWaiter: CompletableDeferred<Unit>? = null

    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the ride starts
    fun start(): Boolean {
        if (isRunning) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 2 * 8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
            record.release()
            return false
        }

        held = false
        turnRequested = false
        isRunning = true
        thread = Thread({ loop(record) }, "peloton-vox").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        post { onMic(PelotonState.Mic.LISTENING) }
        return true
    }

    /** Closes the mic. Any snippet in progress is discarded (pausing means "don't send"). */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.join(STOP_JOIN_MS)
        thread = null
        turnWaiter?.complete(Unit)
        turnWaiter = null
        post { onLevel(0f) }
    }

    /**
     * Half-duplex: suspend until the mic is quiet. If the rider is mid-phrase, the phrase
     * finishes (and is sent) first — bounded by the 10 s snippet cap.
     */
    suspend fun yieldTurn() {
        if (!isRunning || held) return
        val waiter = CompletableDeferred<Unit>()
        turnWaiter = waiter
        turnRequested = true
        withTimeoutOrNull(MAX_YIELD_WAIT_MS) { waiter.await() }
        held = true
    }

    fun releaseTurn() {
        turnRequested = false
        held = false
    }

    // --- Recorder thread ---------------------------------------------------------------

    private fun loop(record: AudioRecord) {
        val effects = attachEffects(record.audioSessionId)
        val detector = VoxDetector()
        val frame = ShortArray(FRAME_SAMPLES)
        val preroll = ArrayDeque<ShortArray>()
        val tail = ArrayList<ShortArray>()
        var writer: AacSnippetWriter? = null
        var wasHeld = false
        var lastLevelPostMs = 0L
        var smoothed = 0f

        fun openWriter(): AacSnippetWriter? = try {
            AacSnippetWriter(
                File(context.cacheDir, "snip_${System.currentTimeMillis()}.m4a"),
                SAMPLE_RATE,
                Constants.AUDIO_BITRATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "encoder open failed", e)
            null
        }

        fun finishAndSend() {
            val file = writer?.finish()
            writer = null
            if (file != null) post { onSnippet(file) }
        }

        fun grantTurn() {
            held = true
            turnRequested = false
            detector.reset()
            preroll.clear()
            tail.clear()
            turnWaiter?.complete(Unit)
            turnWaiter = null
            post { onMic(PelotonState.Mic.YIELDING) }
        }

        try {
            while (isRunning) {
                val n = record.read(frame, 0, FRAME_SAMPLES)
                if (n <= 0) continue
                val level = VoxDetector.levelDbfs(frame, n)

                // Meter: 0..1 over -60..-10 dBFS, fast attack / slow release, ~10 Hz.
                val target = ((level + 60.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
                smoothed = if (target > smoothed) target else smoothed * 0.85f + target * 0.15f
                val now = System.currentTimeMillis()
                if (now - lastLevelPostMs >= LEVEL_POST_MS) {
                    lastLevelPostMs = now
                    val v = if (held) 0f else smoothed
                    post { onLevel(v) }
                }

                if (turnRequested && !detector.capturing) grantTurn()
                if (held) {
                    wasHeld = true
                    continue
                }
                if (wasHeld) {
                    // Pack finished talking: whatever the mic heard meanwhile was them.
                    wasHeld = false
                    detector.reset()
                    preroll.clear()
                    post { onMic(PelotonState.Mic.LISTENING) }
                }

                val chunk = frame.copyOf(n)
                preroll.addLast(chunk)
                while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()

                when (detector.onFrame(level, n * 1000 / SAMPLE_RATE)) {
                    VoxDetector.Event.START -> {
                        writer = openWriter()
                        preroll.forEach { writer?.write(it) }
                        preroll.clear()
                        tail.clear()
                        post { onMic(PelotonState.Mic.ON_AIR) }
                    }
                    VoxDetector.Event.NONE -> if (detector.capturing) {
                        if (detector.lastFrameWasSpeech) {
                            tail.forEach { writer?.write(it) }
                            tail.clear()
                            writer?.write(chunk)
                        } else {
                            tail.add(chunk)
                        }
                    }
                    VoxDetector.Event.STOP_SEND -> {
                        tail.take(TAIL_KEEP_FRAMES).forEach { writer?.write(it) }
                        tail.clear()
                        finishAndSend()
                        if (!turnRequested) post { onMic(PelotonState.Mic.LISTENING) }
                    }
                    VoxDetector.Event.STOP_DISCARD -> {
                        writer?.abort()
                        writer = null
                        tail.clear()
                        if (!turnRequested) post { onMic(PelotonState.Mic.LISTENING) }
                    }
                    VoxDetector.Event.SPLIT -> {
                        tail.forEach { writer?.write(it) }
                        tail.clear()
                        writer?.write(chunk)
                        finishAndSend()
                        if (turnRequested) {
                            grantTurn() // someone's waiting: don't start another 10 s
                        } else {
                            writer = openWriter()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "vox loop failed", e)
            post { onMic(PelotonState.Mic.PAUSED) }
        } finally {
            writer?.abort()
            effects.forEach { it.release() }
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            isRunning = false
            turnWaiter?.complete(Unit)
        }
    }

    private fun attachEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> {
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects += it }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects += it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio effects unavailable", e)
        }
        return effects
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "VoxRecorder"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val PREROLL_FRAMES = 400 / FRAME_MS
        private const val TAIL_KEEP_FRAMES = 250 / FRAME_MS
        private const val LEVEL_POST_MS = 100L
        private const val STOP_JOIN_MS = 600L
        private const val MAX_YIELD_WAIT_MS = 12_000L
    }
}
