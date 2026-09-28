package com.carradio.app.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * "Hey Radio" wake word via the platform SpeechRecognizer (feature-flagged, PROTOCOL §0).
 *
 * Continuous listen → on a final result containing "hey radio":
 *   "... mute"   → stealth-mute the last speaker
 *   "... report" → report the last speaker (§8)
 *   "... repeat" → replay the last burst
 *   anything else → open the mic for a burst (recorder handles endpointing)
 *
 * Degrades gracefully: if recognition is unavailable the manager simply never starts.
 * Known platform limitation: some OEM recognizers play their own chime per listen cycle.
 */
class WakeWordManager(
    private val context: Context,
    private val onMute: () -> Unit,
    private val onRepeat: () -> Unit,
    private val onTalk: () -> Unit,
    private val onReport: () -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var enabled = false
    private var paused = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable || enabled) return
        enabled = true
        handler.post { startListening() }
    }

    fun stop() {
        enabled = false
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    /** Pause while the mic is needed elsewhere (burst recording) or a burst is playing. */
    fun pause() {
        if (!enabled) return
        paused = true
        handler.post { recognizer?.cancel() }
    }

    fun resume() {
        if (!enabled) return
        paused = false
        scheduleRestart(RESTART_AFTER_RESUME_MS)
    }

    private fun startListening() {
        if (!enabled || paused) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            scheduleRestart(ERROR_RESTART_MS)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable { startListening() }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            texts?.any { handleUtterance(it) } // short-circuits on the first match
            scheduleRestart(RESTART_AFTER_RESULT_MS)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            ) {
                recognizer?.destroy()
                recognizer = null
            }
            scheduleRestart(ERROR_RESTART_MS)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Returns true when the utterance triggered a command. */
    private fun handleUtterance(raw: String): Boolean {
        val text = raw.lowercase().replace(",", " ").replace(".", " ")
        val idx = text.indexOf(WAKE_PHRASE)
        if (idx < 0) return false
        val command = text.substring(idx + WAKE_PHRASE.length).trim()
        when {
            command.startsWith("mute") -> onMute()
            command.startsWith("report") -> onReport()
            command.startsWith("repeat") -> onRepeat()
            else -> onTalk()
        }
        return true
    }

    companion object {
        private const val TAG = "WakeWordManager"
        private const val WAKE_PHRASE = "hey radio"
        private const val RESTART_AFTER_RESULT_MS = 300L
        private const val RESTART_AFTER_RESUME_MS = 600L
        private const val ERROR_RESTART_MS = 1500L
    }
}
