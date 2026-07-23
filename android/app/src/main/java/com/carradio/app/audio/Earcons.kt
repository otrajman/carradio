package com.carradio.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * PROTOCOL §9 earcons, synthesized as raw PCM and played through AudioTrack.
 * No asset files. Each `play*` suspends until the cue has finished sounding, so the
 * playback queue can sequence cue → burst without overlap.
 *
 * | cue        | spec                                                        |
 * | incoming   | 60 ms sine burst 880→440 Hz                                 |
 * | mic open   | two 80 ms sines, 520 Hz then 780 Hz                         |
 * | sent       | 300 ms filtered noise sweep 2 kHz→300 Hz, fading            |
 * | muted      | 30 ms sine at 180 Hz                                        |
 * | system     | three 90 ms sines 660/830/990 Hz, 70 ms gaps                |
 * | breadcrumb | incoming pop twice, 120 ms apart                            |
 */
class Earcons {

    suspend fun incoming() = play(popSweep())

    suspend fun micOpen() = play(
        concat(
            tone(520.0, 80),
            silence(20),
            tone(780.0, 80)
        )
    )

    suspend fun sent() = play(noiseSweep(durationMs = 300, startHz = 2000.0, endHz = 300.0))

    suspend fun muted() = play(tone(180.0, 30, amplitude = 0.5))

    suspend fun system() = play(
        concat(
            tone(660.0, 90), silence(70),
            tone(830.0, 90), silence(70),
            tone(990.0, 90)
        )
    )

    suspend fun breadcrumb() = play(
        concat(popSweep(), silence(120), popSweep())
    )

    // --- synthesis ---------------------------------------------------------------------

    /** 60 ms sine sweep 880 → 440 Hz. */
    private fun popSweep(): ShortArray {
        val n = samples(60)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = 880.0 + (440.0 - 880.0) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            out[i] = (sin(phase) * envelope(i, n) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun tone(freqHz: Double, durationMs: Int, amplitude: Double = AMPLITUDE): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val s = sin(2.0 * PI * freqHz * i / SAMPLE_RATE)
            out[i] = (s * envelope(i, n) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** White noise through a one-pole low-pass whose cutoff sweeps down; amplitude fades out. */
    private fun noiseSweep(durationMs: Int, startHz: Double, endHz: Double): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        val random = Random(42)
        var y = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val cutoff = startHz * Math.pow(endHz / startHz, t) // exponential sweep down
            val alpha = 1.0 - exp(-2.0 * PI * cutoff / SAMPLE_RATE)
            val x = random.nextDouble(-1.0, 1.0)
            y += alpha * (x - y)
            val fade = 1.0 - t
            out[i] = (y * fade * envelope(i, n) * AMPLITUDE * 1.6 * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt().toShort()
        }
        return out
    }

    private fun silence(durationMs: Int): ShortArray = ShortArray(samples(durationMs))

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    /** 3 ms linear attack/release to avoid clicks. */
    private fun envelope(i: Int, n: Int): Double {
        val ramp = min(n / 4, (SAMPLE_RATE * 3) / 1000)
        if (ramp == 0) return 1.0
        return when {
            i < ramp -> i.toDouble() / ramp
            i > n - ramp -> (n - i).toDouble() / ramp
            else -> 1.0
        }
    }

    private fun samples(durationMs: Int): Int = SAMPLE_RATE * durationMs / 1000

    // --- output ------------------------------------------------------------------------

    private suspend fun play(samples: ShortArray) {
        if (samples.isEmpty()) return
        val track = withContext(Dispatchers.Default) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { it.write(samples, 0, samples.size) }
        }
        try {
            track.play()
            delay(samples.size * 1000L / SAMPLE_RATE + 40L)
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val AMPLITUDE = 0.35
    }
}
