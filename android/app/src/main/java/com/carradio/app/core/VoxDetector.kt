package com.carradio.app.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * PROTOCOL §16 voice-activated transmit (VOX). Pure state machine: the platform feeds one
 * level per audio frame (dBFS, any frame length) and acts on the returned [Event].
 *
 * - Noise floor = minimum frame level over a sliding window. Speech has inter-word dips, so
 *   the minimum tracks steady wind/road noise and ignores the rider's voice.
 * - Onset: level ≥ floor + [Config.onsetMarginDb] (and ≥ [Config.minOnsetDb]) for
 *   [Config.attackMs]. The platform keeps a pre-roll buffer longer than the attack so the
 *   first syllable isn't clipped.
 * - Release: [Config.hangoverMs] below floor + [Config.sustainMarginDb] ends the snippet;
 *   snippets with less than [Config.minSpeechMs] of speech are discarded (bumps, clicks).
 * - A phrase is streamed in chunks of [Config.maxSnippetMs]: the detector emits
 *   [Event.SPLIT] and keeps capturing into a fresh snippet, so the pack starts hearing a
 *   long phrase ~one chunk after it began instead of after it ended.
 */
class VoxDetector(private val config: Config = Config()) {

    data class Config(
        val warmupMs: Int = 600,
        val floorWindowMs: Int = 2_000,
        val onsetMarginDb: Double = 14.0,
        val sustainMarginDb: Double = 8.0,
        val minOnsetDb: Double = -50.0,
        val attackMs: Int = 120,
        val hangoverMs: Int = 700,
        val minSpeechMs: Int = 250,
        val maxSnippetMs: Int = 3_000
    )

    enum class Event {
        NONE,
        /** Speech onset: open a snippet, write the pre-roll first. */
        START,
        /** Snippet ended with enough speech: finalize and send. */
        STOP_SEND,
        /** Snippet ended without enough speech: throw it away. */
        STOP_DISCARD,
        /** Chunk boundary mid-speech: send this snippet now, keep capturing into a new one. */
        SPLIT
    }

    var capturing: Boolean = false
        private set

    /** Whether the most recent frame counted as speech (for tail trimming). */
    var lastFrameWasSpeech: Boolean = false
        private set

    val noiseFloorDb: Double
        get() = window.minOfOrNull { it.second } ?: SILENCE_DB

    private val window = ArrayDeque<Pair<Int, Double>>() // (durationMs, levelDb)
    private var windowMs = 0
    private var observedMs = 0
    private var aboveMs = 0
    private var snippetMs = 0
    private var speechMs = 0
    private var silenceMs = 0

    fun onFrame(levelDb: Double, durationMs: Int): Event {
        // Floor from the window *before* this frame, so a loud onset can't raise its own bar.
        val floor = if (window.isEmpty()) levelDb else noiseFloorDb
        pushWindow(levelDb, durationMs)
        observedMs += durationMs

        if (!capturing) {
            lastFrameWasSpeech = false
            if (observedMs < config.warmupMs) return Event.NONE
            if (levelDb >= max(floor + config.onsetMarginDb, config.minOnsetDb)) {
                aboveMs += durationMs
                if (aboveMs >= config.attackMs) {
                    capturing = true
                    snippetMs = aboveMs
                    speechMs = aboveMs
                    silenceMs = 0
                    aboveMs = 0
                    lastFrameWasSpeech = true
                    return Event.START
                }
            } else {
                aboveMs = 0
            }
            return Event.NONE
        }

        snippetMs += durationMs
        val speaking = levelDb >= max(floor + config.sustainMarginDb, config.minOnsetDb - 6.0)
        lastFrameWasSpeech = speaking
        if (speaking) {
            speechMs += durationMs
            silenceMs = 0
        } else {
            silenceMs += durationMs
        }

        if (silenceMs >= config.hangoverMs) {
            val enough = speechMs >= config.minSpeechMs
            endSnippet()
            return if (enough) Event.STOP_SEND else Event.STOP_DISCARD
        }
        if (snippetMs >= config.maxSnippetMs) {
            snippetMs = 0
            speechMs = 0
            return Event.SPLIT
        }
        return Event.NONE
    }

    /** Abandon any snippet in progress (pause / half-duplex hold). The floor is kept. */
    fun reset() {
        endSnippet()
        aboveMs = 0
        lastFrameWasSpeech = false
    }

    /** Full reset incl. noise floor and warmup (mic reopened). */
    fun resetAll() {
        reset()
        window.clear()
        windowMs = 0
        observedMs = 0
    }

    private fun endSnippet() {
        capturing = false
        snippetMs = 0
        speechMs = 0
        silenceMs = 0
    }

    private fun pushWindow(levelDb: Double, durationMs: Int) {
        window.addLast(durationMs to levelDb)
        windowMs += durationMs
        while (windowMs - window.first().first >= config.floorWindowMs) {
            windowMs -= window.removeFirst().first
        }
    }

    companion object {
        const val SILENCE_DB = -100.0

        /** dBFS of a PCM-16 frame's RMS; [SILENCE_DB] for digital silence. */
        fun levelDbfs(samples: ShortArray, count: Int = samples.size): Double {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / count)
            if (rms < 1.0) return SILENCE_DB
            return max(SILENCE_DB, 20.0 * log10(rms / 32768.0))
        }
    }
}
