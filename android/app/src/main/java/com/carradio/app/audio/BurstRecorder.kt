package com.carradio.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.carradio.app.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records one voice burst: OGG container + Opus @ ~24 kbps mono 48 kHz, 10 s hard cap
 * (PROTOCOL §4). Requires API 29+ (OutputFormat.OGG / AudioEncoder.OPUS) — matches minSdk.
 *
 * `onFinished` fires exactly once per start(): with the file on success, or null when the
 * recording failed / was cancelled / was too short to contain audio.
 */
class BurstRecorder(
    private val context: Context,
    private val scope: CoroutineScope
) {

    @Volatile
    var isRecording: Boolean = false
        private set

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var vadJob: Job? = null
    private var startedAtMs: Long = 0
    private var onFinished: ((File?) -> Unit)? = null
    private var finished = false

    /**
     * @param autoStopOnSilence simple amplitude-based endpointing for wake-word flows:
     *        stop once we have heard speech followed by ~1.5 s of silence.
     */
    fun start(autoStopOnSilence: Boolean, onFinished: (File?) -> Unit): Boolean {
        if (isRecording) return false
        finished = false
        this.onFinished = onFinished

        val file = File(context.cacheDir, "burst_${System.currentTimeMillis()}.ogg")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setAudioEncodingBitRate(Constants.AUDIO_BITRATE)
            mr.setAudioSamplingRate(Constants.AUDIO_SAMPLE_RATE)
            mr.setAudioChannels(1)
            mr.setMaxDuration(Constants.MAX_BURST_MS)
            mr.setOutputFile(file.absolutePath)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stop()
                }
            }
            mr.setOnErrorListener { _, _, _ -> finish(success = false) }
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed", e)
            try {
                mr.release()
            } catch (_: Exception) {
            }
            file.delete()
            this.onFinished = null
            onFinished(null)
            return false
        }

        recorder = mr
        outFile = file
        startedAtMs = System.currentTimeMillis()
        isRecording = true

        if (autoStopOnSilence) {
            vadJob = scope.launch { watchForSilence(mr) }
        }
        return true
    }

    /** Amplitude-poll VAD: needs ≥ 1 s of audio and some speech before silence can end it. */
    private suspend fun watchForSilence(mr: MediaRecorder) {
        var heardSpeech = false
        var silentMs = 0L
        while (isRecording && recorder === mr) {
            delay(VAD_POLL_MS)
            val amp = try {
                mr.maxAmplitude
            } catch (_: Exception) {
                break
            }
            if (amp > SPEECH_AMPLITUDE) {
                heardSpeech = true
                silentMs = 0
            } else if (heardSpeech) {
                silentMs += VAD_POLL_MS
            }
            val elapsed = System.currentTimeMillis() - startedAtMs
            if (heardSpeech && silentMs >= SILENCE_END_MS && elapsed >= MIN_BURST_MS) {
                stop()
                break
            }
            if (!heardSpeech && elapsed >= NO_SPEECH_TIMEOUT_MS) {
                cancel()
                break
            }
        }
    }

    /** Stop and deliver the file. Safe to call from any thread and when already stopped. */
    fun stop() {
        if (!isRecording) return
        val tooShort = System.currentTimeMillis() - startedAtMs < MIN_BURST_MS
        finish(success = !tooShort)
    }

    /** Discard the current recording without delivering it. */
    fun cancel() {
        if (!isRecording) return
        finish(success = false)
    }

    @Synchronized
    private fun finish(success: Boolean) {
        if (finished) return
        finished = true
        isRecording = false
        vadJob?.cancel()
        vadJob = null

        val mr = recorder
        recorder = null
        var ok = success
        try {
            mr?.stop()
        } catch (e: Exception) {
            // stop() throws if no valid data was captured — treat as failure.
            ok = false
        }
        try {
            mr?.release()
        } catch (_: Exception) {
        }

        val file = outFile
        outFile = null
        val callback = onFinished
        onFinished = null
        if (ok && file != null && file.length() > 0) {
            callback?.invoke(file)
        } else {
            file?.delete()
            callback?.invoke(null)
        }
    }

    companion object {
        private const val TAG = "BurstRecorder"
        private const val VAD_POLL_MS = 250L
        private const val SPEECH_AMPLITUDE = 2500
        private const val SILENCE_END_MS = 1500L
        private const val MIN_BURST_MS = 700L
        private const val NO_SPEECH_TIMEOUT_MS = 4000L
    }
}
