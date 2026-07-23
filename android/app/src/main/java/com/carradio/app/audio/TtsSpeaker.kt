package com.carradio.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * System/text bursts are spoken via android TTS (PROTOCOL §0/§12). `speak` suspends until
 * the utterance completes and supports cancellation (used by skip).
 */
class TtsSpeaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val counter = AtomicLong(0)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
    }

    /** Speaks and suspends until done. Returns false if TTS is unavailable. */
    suspend fun speak(text: String): Boolean {
        val ok = withTimeoutOrNull(5_000) { ready.await() } ?: false
        if (!ok) {
            Log.w(TAG, "TTS unavailable; dropping utterance")
            return false
        }
        tts.language = Locale.getDefault()
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val id = "carradio_${counter.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        // Guard against utterances that never report completion.
        val timeoutMs = 4_000L + text.length * 90L
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                done.invokeOnCompletion { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation {
                    pending.remove(id)
                    tts.stop()
                }
            }
        }
        pending.remove(id)
        return true
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "TtsSpeaker"
    }
}
