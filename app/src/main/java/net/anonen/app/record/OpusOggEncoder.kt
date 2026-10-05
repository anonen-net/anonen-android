package net.anonen.app.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import net.anonen.app.core.DiagnosticsLog
import java.io.File

object OpusOggEncoder {
    fun encode(
        wavBytes: ByteArray,
        tempDir: File,
    ): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pcm = extractPcm16(wavBytes) ?: return null
        return try {
            encodePcm(pcm, tempDir)
        } catch (e: Exception) {
            DiagnosticsLog.log("Opus エンコード失敗 → WAV 送信: ${e.javaClass.simpleName}")
            null
        }
    }

    internal fun extractPcm16(wav: ByteArray): ByteArray? {
        if (wav.size < WavEncoder.HEADER_SIZE) return null
        if (!wav.hasAscii(0, "RIFF") || !wav.hasAscii(8, "WAVE")) return null
        var pos = 12
        var fmtOk = false
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = wav.intLe(pos + 4)
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    if (size < 16 || pos + 24 > wav.size) return null
                    val audioFormat = wav.shortLe(pos + 8)
                    val channels = wav.shortLe(pos + 10)
                    val sampleRate = wav.intLe(pos + 12)
                    val bits = wav.shortLe(pos + 22)
                    if (audioFormat != 1 || channels != 1 ||
                        sampleRate != SAMPLE_RATE || bits != 16
                    ) {
                        return null
                    }
                    fmtOk = true
                }
                "data" -> {
                    if (!fmtOk) return null
                    val end = minOf(pos + 8 + size, wav.size)
                    return wav.copyOfRange(pos + 8, end)
                }
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun encodePcm(
        pcm: ByteArray,
        tempDir: File,
    ): ByteArray {
        val tmp = File.createTempFile("opus-encoder", ".ogg", tempDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            var trackIndex = -1
            var inOffset = 0
            var inputDone = false
            var sawEos = false
            var presentationUs = 0L
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + ENCODE_DEADLINE_MS

            while (!sawEos) {
                if (System.currentTimeMillis() > deadline) {
                    throw IllegalStateException("Opus encode deadline exceeded")
                }

                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = checkNotNull(codec.getInputBuffer(inIndex))
                    buf.clear()
                    val chunk = minOf(buf.capacity(), pcm.size - inOffset)
                    if (chunk <= 0) {
                        codec.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            presentationUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        buf.put(pcm, inOffset, chunk)
                        codec.queueInputBuffer(inIndex, 0, chunk, presentationUs, 0)
                        inOffset += chunk

                        presentationUs += chunk.toLong() * 1_000_000L / (2L * SAMPLE_RATE)
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                while (!sawEos) {
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val isConfig =
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (info.size > 0 && !isConfig && muxerStarted) {
                                val outBuf = checkNotNull(codec.getOutputBuffer(outIndex))
                                muxer.writeSampleData(trackIndex, outBuf, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawEos = true
                            }
                        }
                        else -> break
                    }
                    if (!sawEos) outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
            codec.stop()
            if (muxerStarted) muxer.stop()
            return tmp.readBytes()
        } finally {
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            tmp.delete()
        }
    }

    private fun ByteArray.hasAscii(
        offset: Int,
        text: String,
    ): Boolean {
        if (offset + text.length > size) return false
        return text.withIndex().all { (i, c) -> this[offset + i].toInt() == c.code }
    }

    private fun ByteArray.intLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private const val SAMPLE_RATE = 16000
    private const val BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L
    private const val ENCODE_DEADLINE_MS = 30_000L
}
