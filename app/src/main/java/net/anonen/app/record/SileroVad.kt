package net.anonen.app.record

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import net.anonen.app.core.DiagnosticsLog
import java.io.Closeable
import java.io.File
import java.io.IOException

class SileroVad private constructor(
    private val vad: Vad,
    private val threshold: Float,
) : VoiceActivityDetector, Closeable {
    override fun reset() {
        vad.reset()
    }

    override fun isVoice(frame: ShortArray): Boolean {
        val floats = FloatArray(frame.size) { frame[it] / FULL_SCALE }
        return vad.compute(floats) > threshold
    }

    override fun close() {
        runCatching { vad.release() }
    }

    companion object {
        const val FRAME_SAMPLES = 480

        private const val ASSET_NAME = "silero_vad_v4.onnx"
        private const val DEFAULT_THRESHOLD = 0.3f
        private const val SAMPLE_RATE = 16000
        private const val FULL_SCALE = 32768f

        fun fromAsset(
            context: Context,
            threshold: Float = DEFAULT_THRESHOLD,
        ): SileroVad? {
            val extracted =
                runCatching { extractAssetIfNeeded(context, ASSET_NAME) }
                    .onFailure { DiagnosticsLog.log("Silero VAD の展開に失敗: ${it.message}") }
                    .getOrNull() ?: return null
            return runCatching {
                val config =
                    VadModelConfig(
                        sileroVadModelConfig =
                            SileroVadModelConfig(
                                model = extracted.file.absolutePath,
                                threshold = threshold,
                            ),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                    )
                val vad = Vad(config = config)
                SileroVad(vad, threshold)
            }.onFailure {
                DiagnosticsLog.log("Silero VAD 初期化失敗: ${it.message}")

                if (!extracted.freshlyExtracted) extracted.file.delete()
            }.getOrNull()
        }

        private class ExtractedAsset(
            val file: File,
            val freshlyExtracted: Boolean,
        )

        private fun extractAssetIfNeeded(
            context: Context,
            assetName: String,
        ): ExtractedAsset {
            val dest = File(context.filesDir, assetName)
            if (dest.exists()) return ExtractedAsset(dest, freshlyExtracted = false)

            val tmp = File(context.filesDir, "$assetName.tmp")
            try {
                context.assets.open(assetName).use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                if (!tmp.renameTo(dest)) throw IOException("VAD モデルを置けません")
            } finally {
                tmp.delete()
            }
            return ExtractedAsset(dest, freshlyExtracted = true)
        }
    }
}
