package com.carradio.app.peloton

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM into an AAC-LC `.m4a` — the same container/codec iOS sends
 * (PROTOCOL §4: receivers play by URL and don't care). One instance per snippet, used from
 * the recorder thread only.
 */
class AacSnippetWriter(
    val file: File,
    private val sampleRate: Int,
    bitRate: Int
) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesQueued = 0L
    private var released = false

    val durationMs: Long get() = samplesQueued * 1000 / sampleRate

    init {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int = pcm.size) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val input = codec.getInputBuffer(index) ?: continue
            input.clear()
            val n = minOf(count - offset, input.remaining() / 2)
            input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, ptsUs(), 0)
            samplesQueued += n
            offset += n
            drain(endOfStream = false)
        }
    }

    /** Flushes the encoder and closes the file. Returns the file, or null if nothing encoded. */
    fun finish(): File? {
        if (released) return null
        return try {
            var queuedEos = false
            while (!queuedEos) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queuedEos = true
                } else {
                    drain(endOfStream = false)
                }
            }
            drain(endOfStream = true)
            val ok = muxerStarted
            release()
            if (ok && file.length() > 0) file else null.also { file.delete() }
        } catch (_: Exception) {
            abort()
            null
        }
    }

    fun abort() {
        release()
        file.delete()
    }

    private fun drain(endOfStream: Boolean) {
        var idlePolls = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                // At EOS, give the encoder up to ~1 s to flush rather than spinning forever.
                index == MediaCodec.INFO_TRY_AGAIN_LATER ->
                    if (!endOfStream || ++idlePolls > MAX_EOS_POLLS) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val out: ByteBuffer? = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && info.size > 0 && muxerStarted && !isConfig) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun ptsUs(): Long = samplesQueued * 1_000_000L / sampleRate

    private fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        try {
            if (muxerStarted) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10_000L
        private const val MAX_EOS_POLLS = 100
    }
}
