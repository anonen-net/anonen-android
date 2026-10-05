package net.anonen.app.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.SystemClock
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.RecordingAudioSource
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class RecordingResult(
    val rawWav: ByteArray,
    val processedWav: ByteArray?,
)

class WavRecorder(
    private val outputSampleRate: Int = PcmAudioProcessor.TARGET_SAMPLE_RATE,
) {
    @Volatile
    private var recording = false
    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var readerThread: Thread? = null
    private var captureSampleRate: Int = outputSampleRate

    @Volatile
    private var drainUntilMs: Long = 0L

    private val allPcm16kBuffer = ByteArrayOutputStream()
    private val speechPcm16kBuffer = ByteArrayOutputStream()

    var audioSource: RecordingAudioSource = RecordingAudioSource.MIC
    var spectralEqEnabled: Boolean = true

    @Volatile
    var vad: VoiceActivityDetector = PassThroughVad

    @Volatile
    var maxSeconds: Int? = null

    var onAutoStop: (() -> Unit)? = null

    var onAutoStopWarning: (() -> Unit)? = null

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return false

        val prepared = createAudioRecord(audioSource) ?: return false
        val record = prepared.record
        captureSampleRate = prepared.sampleRate
        enableAgc(record.audioSessionId)

        if (!runCatching { record.startRecording() }.isSuccess ||
            record.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            agc?.runCatching { release() }
            agc = null
            record.release()
            return false
        }

        allPcm16kBuffer.reset()
        speechPcm16kBuffer.reset()
        drainUntilMs = 0L
        audioRecord = record
        recording = true
        val capturedMaxSeconds = maxSeconds
        DiagnosticsLog.log(
            "録音 source=${prepared.source} rate=${captureSampleRate}Hz " +
                "上限=${capturedMaxSeconds?.let { "${it}s" } ?: "なし"}",
        )

        val limitTracker =
            RecordingLimitTracker(capturedMaxSeconds, outputSampleRate * BYTES_PER_SAMPLE)
        val capturedVad = vad

        readerThread =
            thread(name = "wav-reader") {
                val downsampler = StreamingDownsampler(captureSampleRate, outputSampleRate)
                val smoothedVad = SmoothedVad(capturedVad)
                smoothedVad.reset()
                val frameAccum = ShortArray(FRAME_SAMPLES)
                var framePos = 0

                fun feedSamples(samples: ShortArray) {
                    for (s in samples) {
                        writeShort(allPcm16kBuffer, s)
                        frameAccum[framePos++] = s
                        if (framePos == FRAME_SAMPLES) {
                            val emitted = smoothedVad.feedFrame(frameAccum.copyOf())
                            for (frame in emitted) {
                                writeFrame(speechPcm16kBuffer, frame)
                            }
                            framePos = 0
                        }
                    }
                }

                fun processChunk(
                    chunk: ByteArray,
                    bytesRead: Int,
                ) {
                    val inputSamples = bytesToShorts(chunk, bytesRead)
                    val resampled =
                        if (downsampler.isPassthrough) {
                            inputSamples
                        } else {
                            downsampler.process(inputSamples)
                        }
                    feedSamples(resampled)
                }

                val chunk = ByteArray(CHUNK_BYTES)
                while (recording) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        processChunk(chunk, n)
                        when (limitTracker.onBuffered(allPcm16kBuffer.size().toLong())) {
                            RecordingLimitTracker.Event.WARNING -> {
                                DiagnosticsLog.log(
                                    "録音上限まで残り約" +
                                        "${RecordingLimitTracker.WARNING_LEAD_SECONDS}秒 → 停止前警告",
                                )
                                onAutoStopWarning?.invoke()
                            }
                            RecordingLimitTracker.Event.AUTO_STOP -> {
                                DiagnosticsLog.log(
                                    "録音上限 ${capturedMaxSeconds}s 到達 → 自動停止（転写へ）",
                                )
                                recording = false
                                onAutoStop?.invoke()
                            }
                            null -> Unit
                        }
                    } else if (n < 0) {
                        recording = false
                    }
                }
                while (SystemClock.elapsedRealtime() < drainUntilMs) {
                    val n =
                        record.read(
                            chunk,
                            0,
                            chunk.size,
                            AudioRecord.READ_NON_BLOCKING,
                        )
                    if (n > 0) {
                        processChunk(chunk, n)
                    } else {
                        Thread.sleep(DRAIN_POLL_MS)
                    }
                }
                if (!downsampler.isPassthrough) {
                    feedSamples(downsampler.finish())
                }
                if (framePos > 0) {
                    for (i in framePos until FRAME_SAMPLES) frameAccum[i] = 0
                    val emitted = smoothedVad.feedFrame(frameAccum.copyOf())
                    for (frame in emitted) {
                        writeFrame(speechPcm16kBuffer, frame)
                    }
                }
            }
        return true
    }

    fun stop(): RecordingResult? {
        val t0 = SystemClock.elapsedRealtime()
        val buffers = finishAndDrain(drain = true) ?: return null
        return process(buffers, drainMs = SystemClock.elapsedRealtime() - t0)
    }

    fun cancel() {
        finishAndDrain(drain = false)
    }

    fun stopAndHold(): HeldRecording? = finishAndDrain(drain = false)?.let { HeldRecording(it) }

    fun finish(held: HeldRecording): RecordingResult = process(held.buffers, drainMs = 0L)

    class HeldRecording internal constructor(
        internal val buffers: DrainedBuffers,
    )

    private fun process(
        buffers: DrainedBuffers,
        drainMs: Long,
    ): RecordingResult {
        val t1 = SystemClock.elapsedRealtime()
        val eq = if (spectralEqEnabled) SpectralMatchEq() else null

        val processedPcm = PcmAudioProcessor.postProcessSpeech(buffers.speechPcm, eq)
        val t2 = SystemClock.elapsedRealtime()
        val rawWav = WavEncoder.pcm16ToWav(buffers.allPcm, outputSampleRate, 1)
        val processedWav = processedPcm?.let { WavEncoder.pcm16ToWav(it, outputSampleRate, 1) }
        val t3 = SystemClock.elapsedRealtime()
        DiagnosticsLog.log(
            "stop内訳 drain=${drainMs}ms EQ+norm=${t2 - t1}ms wav=${t3 - t2}ms 合計=${drainMs + t3 - t1}ms" +
                if (processedWav == null) " 発話区間なし" else "",
        )
        return RecordingResult(rawWav = rawWav, processedWav = processedWav)
    }

    internal class DrainedBuffers(
        val allPcm: ByteArray,
        val speechPcm: ByteArray,
    )

    @Synchronized
    private fun finishAndDrain(drain: Boolean): DrainedBuffers? {
        if (audioRecord == null) return null
        drainUntilMs =
            if (drain) {
                SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
            } else {
                0L
            }
        recording = false
        readerThread?.join(DRAIN_TIMEOUT_MS + 1500L)
        readerThread = null
        audioRecord?.runCatching { stop() }
        audioRecord?.release()
        audioRecord = null
        agc?.runCatching { release() }
        agc = null
        val allPcm = allPcm16kBuffer.toByteArray()
        val speechPcm = speechPcm16kBuffer.toByteArray()
        allPcm16kBuffer.reset()
        speechPcm16kBuffer.reset()
        return if (allPcm.isEmpty()) null else DrainedBuffers(allPcm, speechPcm)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: RecordingAudioSource): PreparedAudioRecord? {
        for (candidateSource in source.fallbackOrder()) {
            for (sampleRate in CAPTURE_SAMPLE_RATES) {
                val minBufferSize =
                    AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                if (minBufferSize <= 0) continue

                val record =
                    runCatching {
                        AudioRecord(
                            candidateSource.androidSource,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(minBufferSize * 2, sampleRate / 10 * BYTES_PER_SAMPLE),
                        )
                    }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return PreparedAudioRecord(record, sampleRate, candidateSource)
                }
                record.release()
            }
        }
        return null
    }

    private fun enableAgc(sessionId: Int) {
        agc =
            runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                } else {
                    null
                }
            }.getOrNull()
        DiagnosticsLog.log("AGC=${if (agc?.enabled == true) "on" else "off"}")
    }

    private data class PreparedAudioRecord(
        val record: AudioRecord,
        val sampleRate: Int,
        val source: RecordingAudioSource,
    )

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_BYTES = 4096
        const val DRAIN_TIMEOUT_MS = 100L
        const val DRAIN_POLL_MS = 10L
        const val FRAME_SAMPLES = 480

        val CAPTURE_SAMPLE_RATES =
            intArrayOf(48000, 44100, 32000, PcmAudioProcessor.TARGET_SAMPLE_RATE)

        val RecordingAudioSource.androidSource: Int
            get() =
                when (this) {
                    RecordingAudioSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    RecordingAudioSource.MIC -> MediaRecorder.AudioSource.MIC
                    RecordingAudioSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
                    RecordingAudioSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
                }

        fun RecordingAudioSource.fallbackOrder(): List<RecordingAudioSource> =
            when (this) {
                RecordingAudioSource.VOICE_RECOGNITION ->
                    listOf(RecordingAudioSource.VOICE_RECOGNITION, RecordingAudioSource.MIC)
                RecordingAudioSource.MIC ->
                    listOf(RecordingAudioSource.MIC, RecordingAudioSource.VOICE_RECOGNITION)
                RecordingAudioSource.UNPROCESSED ->
                    listOf(
                        RecordingAudioSource.UNPROCESSED,
                        RecordingAudioSource.VOICE_RECOGNITION,
                        RecordingAudioSource.MIC,
                    )
                RecordingAudioSource.CAMCORDER ->
                    listOf(
                        RecordingAudioSource.CAMCORDER,
                        RecordingAudioSource.MIC,
                        RecordingAudioSource.VOICE_RECOGNITION,
                    )
            }

        fun bytesToShorts(
            bytes: ByteArray,
            length: Int,
        ): ShortArray {
            val sampleCount = length / BYTES_PER_SAMPLE
            val samples = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            return samples
        }

        fun writeShort(
            out: ByteArrayOutputStream,
            value: Short,
        ) {
            val v = value.toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        fun writeFrame(
            out: ByteArrayOutputStream,
            frame: ShortArray,
        ) {
            val bytes = ByteArray(frame.size * BYTES_PER_SAMPLE)
            for (i in frame.indices) {
                val v = frame[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }
}
