package net.anonen.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

object DiagnosticsLog {
    private const val MEMORY_MAX = 500
    private const val FILE_MAX_BYTES = 512 * 1024L
    private const val FILE_KEEP_BYTES = 256 * 1024

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    @Volatile
    private var sinkFile: File? = null
    private val fileLock = Any()

    fun attachFileSink(file: File) {
        sinkFile = file
    }

    fun log(message: String) {
        val ts =
            runCatching {
                android.text.format.DateFormat
                    .format("MM-dd HH:mm:ss", System.currentTimeMillis())
                    .toString()
            }.getOrDefault("")
        val line = "$ts  $message"
        _events.value = (listOf(line) + _events.value).take(MEMORY_MAX)
        runCatching { android.util.Log.i("AnonenFlow", message) }
        appendToFile(line)
    }

    fun fullLogText(): String {
        val fileText =
            synchronized(fileLock) {
                runCatching {
                    sinkFile?.takeIf { it.isFile }?.readText()
                }.getOrNull()
            }
        return fileText?.takeIf { it.isNotBlank() }
            ?: _events.value.asReversed().joinToString("\n")
    }

    fun clear() {
        _events.value = emptyList()
        synchronized(fileLock) { runCatching { sinkFile?.delete() } }
    }

    private fun appendToFile(line: String) {
        val file = sinkFile ?: return
        synchronized(fileLock) {
            runCatching {
                if (file.length() > FILE_MAX_BYTES) {
                    val bytes = file.readBytes()
                    val from = (bytes.size - FILE_KEEP_BYTES).coerceAtLeast(0)
                    file.writeBytes(bytes.copyOfRange(from, bytes.size))
                }
                file.appendText(line + "\n")
            }
        }
    }
}
