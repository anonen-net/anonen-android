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
            val samples = wavBytesToFloat(request.wavBytes)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate = 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                if (request.isCancelled()) return cancelled()
                val text = result.text.trim()
                if (text.isEmpty()) {
                    TranscriptionResult.Success("", System.currentTimeMillis() - startedAt)
                } else {
                    TranscriptionResult.Success(text, System.currentTimeMillis() - startedAt)
                }
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            DiagnosticsLog.log("ローカルASR失敗: ${e.message}")
            TranscriptionResult.Failure(
                AsrError(
                    AsrError.Kind.CONFIG,
                    "スマホの中のモデルで、文字起こしできませんでした",
                    "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                ),
            )
        }
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
        private fun wavBytesToFloat(wav: ByteArray): FloatArray {
            val headerSize = 44
            if (wav.size <= headerSize) return FloatArray(0)
            val pcm = ByteBuffer.wrap(wav, headerSize, wav.size - headerSize)
            pcm.order(ByteOrder.LITTLE_ENDIAN)
            val sampleCount = (wav.size - headerSize) / 2
            val floats = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                floats[i] = pcm.short / 32768f
            }
            return floats
        }
    }
}
