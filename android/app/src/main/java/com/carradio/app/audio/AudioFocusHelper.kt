package com.carradio.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * PROTOCOL §10: hold AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK while playing a burst or recording,
 * so music/nav apps duck and come back afterwards. Re-entrant (queue + recorder may overlap).
 */
class AudioFocusHelper(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var holders = 0

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { /* transient holder; nothing to do on loss */ }
            .build()

    @Synchronized
    fun acquire() {
        holders++
        if (holders == 1) {
            audioManager.requestAudioFocus(request)
        }
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            audioManager.abandonAudioFocusRequest(request)
        }
    }
}
