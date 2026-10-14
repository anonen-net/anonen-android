package net.anonen.app.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import net.anonen.app.core.AsrError
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.TranscriptionRequest
import net.anonen.app.core.TranscriptionResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun senseVoiceLanguage(languageHint: String): String =
    when (languageHint) {
        "", "auto" -> "auto"
        "zh", "zh-Hans", "zh-Hant" -> "zh"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "yue" -> "yue"
        else -> "auto"
    }

internal object SpeechChunks {
    const val MAX_CHUNK_MS = 30_000

    const val SEARCH_MS = 10_000

    const val FRAME_MS = 30

    const val MIN_LAST_CHUNK_MS = 3_000

    const val SUSPICIOUS_EMPTY_MS = 10_000

    sealed interface Outcome {
        data object Cancelled : Outcome

        data class Text(val text: String) : Outcome

        data class Gap(
            val chunkIndex: Int,
            val chunkMs: Long,
            val partialText: String,
        ) : Outcome
    }

    fun transcribe(
        wav: ByteArray,
        sampleRate: Int,
        isCancelled: () -> Boolean,
        decode: (FloatArray) -> String,
    ): Outcome {
        val sampleCount = if (wav.size > WAV_HEADER_SIZE) (wav.size - WAV_HEADER_SIZE) / 2 else 0
        val chunks = ranges(sampleCount, sampleRate, frameEnergies(wav, WAV_HEADER_SIZE, sampleCount, sampleRate))
        val texts = ArrayList<String>(chunks.size)
        for (range in chunks) {
            if (isCancelled()) return Outcome.Cancelled

            texts += if (range.isEmpty()) "" else decode(floatsOf(wav, range))
        }
        if (isCancelled()) return Outcome.Cancelled
        if (texts.any { it.isNotBlank() }) {
            val suspicious = sampleRate.toLong() * SUSPICIOUS_EMPTY_MS / 1000
            for ((i, range) in chunks.withIndex()) {
                if (range.count() >= suspicious && texts[i].isBlank()) {
                    return Outcome.Gap(i, range.count() * 1000L / sampleRate, join(texts))
                }
            }
        }
        return Outcome.Text(join(texts))
    }

    private const val WAV_HEADER_SIZE = 44

    private fun floatsOf(
        wav: ByteArray,
        range: IntRange,
    ): FloatArray {
        val pcm = ByteBuffer.wrap(wav, WAV_HEADER_SIZE + range.first * 2, range.count() * 2)
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(range.count()) { pcm.short / 32768f }
    }

    fun ranges(
        totalSamples: Int,
        sampleRate: Int,
        energies: DoubleArray,
    ): List<IntRange> {
        val frameLen = sampleRate * FRAME_MS / 1000
        val maxLen = sampleRate.toLong() * MAX_CHUNK_MS / 1000
        val searchLen = sampleRate.toLong() * SEARCH_MS / 1000
        val minLast = sampleRate.toLong() * MIN_LAST_CHUNK_MS / 1000
        val out = ArrayList<IntRange>()
        var start = 0
        while (totalSamples - start > maxLen) {
            val windowEnd = minOf(start + maxLen, totalSamples - minLast)
            val windowStart = windowEnd - searchLen
            val firstFrame = ((windowStart + frameLen - 1) / frameLen).toInt()
            val lastFrame = (windowEnd / frameLen).toInt() - 1
            var best = lastFrame
            for (f in lastFrame downTo firstFrame) {
                if (energies[f] < energies[best]) best = f
            }
            val cut = best * frameLen + frameLen / 2
            out += start until cut
            start = cut
        }
        out += start until totalSamples
        return out
    }

    fun frameEnergies(
        pcm: ByteArray,
        offset: Int,
        sampleCount: Int,
        sampleRate: Int,
    ): DoubleArray {
        val frameLen = sampleRate * FRAME_MS / 1000
        val frames = sampleCount / frameLen
        return DoubleArray(frames) { f ->
            var sum = 0.0
            val base = offset + f * frameLen * 2
            for (k in 0 until frameLen) {
                val lo = pcm[base + k * 2].toInt() and 0xFF
                val hi = pcm[base + k * 2 + 1].toInt()
                val v = ((hi shl 8) or lo).toShort().toDouble()
                sum += v * v
            }
            sum / frameLen
        }
    }

    fun join(texts: List<String>): String {
        val parts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && !isHanOrKana(sb.last()) && !isHanOrKana(p.first())) sb.append(' ')
            sb.append(p)
        }
        return dropSpacesBetweenHanOrKana(sb.toString())
    }

    private fun dropSpacesBetweenHanOrKana(text: String): String {
        if (' ' !in text && '\u3000' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\u3000') {
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\u3000')) j++
                val before = sb.lastOrNull()
                val after = text.getOrNull(j)
                if (before != null && after != null && isHanOrKana(before) && isHanOrKana(after)) {
                    i = j
                    continue
                }
                sb.append(text, i, j)
                i = j
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isHanOrKana(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}

internal inline fun <S, R> useStream(
    create: () -> S,
    release: (S) -> Unit,
    block: (S) -> R,
): R {
    val stream = create()
    try {
        return block(stream)
    } finally {
        release(stream)
    }
}

class LocalAsrEngine(
    private val modelDir: File,
    private val languageHint: String = "auto",
) : TranscriptionEngine {
    private var recognizer: OfflineRecognizer? = null

    private val lock = Any()

    @Volatile
    var isReleased: Boolean = false
        private set

    override fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        synchronized(lock) { transcribeLocked(request) }

    private fun cancelled(): TranscriptionResult =
        TranscriptionResult.Failure(AsrError(AsrError.Kind.CANCELLED, "やめました", "cancelled by user"))

    private fun transcribeLocked(request: TranscriptionRequest): TranscriptionResult {
        val startedAt = System.currentTimeMillis()

        if (request.isCancelled()) return cancelled()
        return try {
            val rec = getOrCreateRecognizer()
            var chunkCount = 0
            val outcome =
                SpeechChunks.transcribe(request.wavBytes, SAMPLE_RATE, request.isCancelled) { samples ->
                    chunkCount++
                    decodeOne(rec, samples)
                }
            if (chunkCount > 1) DiagnosticsLog.log("ローカルASR: $chunkCount かたまりに分けて文字にした")
            when (outcome) {
                SpeechChunks.Outcome.Cancelled -> cancelled()
                is SpeechChunks.Outcome.Text ->
                    TranscriptionResult.Success(outcome.text, System.currentTimeMillis() - startedAt)
                is SpeechChunks.Outcome.Gap -> {
                    DiagnosticsLog.log("ローカルASR: ${outcome.chunkIndex} 番目のかたまり（${outcome.chunkMs}ms）が空 → 失敗にする")
                    TranscriptionResult.Failure(
                        AsrError(
                            AsrError.Kind.CONFIG,
                            FAILED_MESSAGE,
                            "chunk ${outcome.chunkIndex} came back empty",
                            partialText = outcome.partialText,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticsLog.log("ローカルASR失敗: ${e.message}")
            TranscriptionResult.Failure(
                AsrError(
                    AsrError.Kind.CONFIG,
                    FAILED_MESSAGE,
                    "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                ),
            )
        }
    }

    private fun decodeOne(
        rec: OfflineRecognizer,
        samples: FloatArray,
    ): String =
        useStream(create = { rec.createStream() }, release = { it.release() }) { stream ->
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text
        }

    fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            isReleased = true
        }
    }

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        check(!isReleased) { "解放済みのエンジン" }
        val modelFile = File(modelDir, "model.int8.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        require(modelFile.exists()) { "モデルファイルが見つかりません: ${modelFile.path}" }
        require(tokensFile.exists()) { "トークンファイルが見つかりません: ${tokensFile.path}" }

        val lang = senseVoiceLanguage(languageHint)

        val config =
            OfflineRecognizerConfig(
                modelConfig =
                    OfflineModelConfig(
                        senseVoice =
                            OfflineSenseVoiceModelConfig(
                                model = modelFile.absolutePath,
                                language = lang,
                                useInverseTextNormalization = true,
                            ),
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                    ),
            )
        val rec = OfflineRecognizer(config = config)
        recognizer = rec
        DiagnosticsLog.log("ローカルASR初期化完了 (SenseVoice, lang=$lang)")
        return rec
    }

    companion object {
        private const val SAMPLE_RATE = 16000

        private const val FAILED_MESSAGE = "スマホの中のモデルで、文字起こしできませんでした"
    }
}
