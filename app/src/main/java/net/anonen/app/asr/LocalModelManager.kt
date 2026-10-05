package net.anonen.app.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.LocalModelInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface ModelState {
    data object NotDownloaded : ModelState

    data class Downloading(val progress: Float) : ModelState

    data object Ready : ModelState

    data class Error(val message: String) : ModelState
}

class LocalModelManager(
    private val modelsDir: File,
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build(),
) {
    constructor(context: Context, httpClient: OkHttpClient) :
        this(File(context.filesDir, "models"), httpClient)

    private val stateFlows = mutableMapOf<String, MutableStateFlow<ModelState>>()

    private var engine: LocalAsrEngine? = null
    private var engineDir: File? = null
    private var engineLanguage: String? = null

    fun modelDir(info: LocalModelInfo): File = File(modelsDir, info.dirName)

    @Synchronized
    fun engineFor(
        info: LocalModelInfo,
        languageHint: String,
    ): LocalAsrEngine {
        val dir = modelDir(info)
        val current = engine
        if (current != null &&
            engineDir == dir &&
            engineLanguage == languageHint &&
            !current.isReleased
        ) {
            return current
        }
        releaseEngine()
        return LocalAsrEngine(modelDir = dir, languageHint = languageHint).also {
            engine = it
            engineDir = dir
            engineLanguage = languageHint
            DiagnosticsLog.log("ローカル転写エンジンを用意: ${info.id}")
        }
    }

    @Synchronized
    fun releaseEngine() {
        engine?.let {
            it.release()
            DiagnosticsLog.log("ローカル転写エンジンを解放")
        }
        engine = null
        engineDir = null
        engineLanguage = null
    }

    private fun stampFile(info: LocalModelInfo): File = File(modelDir(info), ".verified")

    private fun expectedStamp(info: LocalModelInfo): String =
        info.files.joinToString("\n") { "${it.fileName} ${it.sha256}" }

    fun isReady(info: LocalModelInfo): Boolean {
        val dir = modelDir(info)

        if (info.files.any { File(dir, it.fileName).length() <= 0L }) return false

        return runCatching { stampFile(info).readText() }.getOrNull() == expectedStamp(info)
    }

    fun stateFlow(info: LocalModelInfo): StateFlow<ModelState> =
        synchronized(stateFlows) {
            stateFlows.getOrPut(info.id) {
                MutableStateFlow(if (isReady(info)) ModelState.Ready else ModelState.NotDownloaded)
            }
        }

    suspend fun download(info: LocalModelInfo) {
        val flow =
            synchronized(stateFlows) {
                stateFlows.getOrPut(info.id) { MutableStateFlow(ModelState.NotDownloaded) }
            }
        if (flow.value is ModelState.Downloading) return

        flow.value = ModelState.Downloading(0f)
        withContext(Dispatchers.IO) {
            try {
                val dir = modelDir(info)
                dir.mkdirs()
                val totalSize = info.totalSizeBytes
                var downloadedTotal = 0L

                stampFile(info).delete()

                for (file in info.files) {
                    val dest = File(dir, file.fileName)
                    val tmp = File(dir, "${file.fileName}.tmp")
                    try {
                        downloadFile(file.url, tmp, file.sha256) { bytesRead ->
                            downloadedTotal += bytesRead
                            flow.value =
                                ModelState.Downloading(
                                    (downloadedTotal.toFloat() / totalSize).coerceIn(0f, 1f),
                                )
                        }

                        if (!tmp.renameTo(dest)) {
                            throw IOException("モデルの保存に失敗しました: ${file.fileName}")
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }

                stampFile(info).writeText(expectedStamp(info))

                DiagnosticsLog.log("ローカルモデルダウンロード完了: ${info.id}")
                flow.value = ModelState.Ready
            } catch (e: Exception) {
                DiagnosticsLog.log("モデルダウンロード失敗: ${e.message}")

                flow.value =
                    ModelState.Error(
                        when (e) {
                            is IntegrityError -> "受け取ったモデルが壊れていました"
                            is IOException -> "ダウンロードできませんでした"
                            else -> "入れられませんでした"
                        },
                    )
            }
        }
    }

    fun delete(info: LocalModelInfo) {
        val dir = modelDir(info)

        releaseEngine()
        dir.deleteRecursively()
        synchronized(stateFlows) {
            stateFlows[info.id]?.value = ModelState.NotDownloaded
        }
        DiagnosticsLog.log("ローカルモデル削除: ${info.id}")
    }

    private fun downloadFile(
        url: String,
        dest: File,
        expectedSha256: String,
        onProgress: (Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ダウンロード失敗: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("空レスポンス")

            val declaredLength = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(8192)
                val source = body.byteStream()
                var read: Int
                while (source.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    written += read
                    onProgress(read.toLong())
                }
                out.flush()
            }

            if (declaredLength >= 0 && written != declaredLength) {
                throw IOException("ダウンロードが途中で終了しました ($written/$declaredLength)")
            }
            if (written == 0L) {
                throw IOException("空のファイルを受け取りました")
            }

            val got = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (!got.equals(expectedSha256, ignoreCase = true)) {
                throw IntegrityError(dest.name)
            }
        }
    }
}

private class IntegrityError(fileName: String) : IOException("モデルの照合に失敗: $fileName")
