package net.anonen.app.history

import kotlinx.coroutines.flow.Flow
import net.anonen.app.pipeline.TranscriptionPipeline
import java.io.File

class HistoryRepository(
    private val dao: HistoryDao,
    private val audioDir: File? = null,
    private val compactAfterClear: (suspend () -> Unit)? = null,
    private val inTransaction: suspend (block: suspend () -> Boolean) -> Boolean = { it() },
) {
    val entries: Flow<List<HistoryEntry>> = dao.observeAll()

    suspend fun add(
        text: String,
        durationMs: Long,
        limit: Int,
        wavBytes: ByteArray? = null,
        model: String? = null,
        audioLimit: Int = 0,
    ) {
        val audioFileName = saveAudio(wavBytes)
        try {
            dao.insert(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    text = text,
                    durationMs = durationMs,
                    audioFileName = audioFileName,
                    model = model,
                ),
            )
        } catch (e: Throwable) {
            deleteAudio(audioFileName)
            throw e
        }
        trimTo(limit)
        dropAudioBeyond(audioLimit)
    }

    suspend fun trimTo(limit: Int) {
        if (limit <= 0) return
        dao.entriesBeyondLimit(limit).forEach { deleteAudio(it.audioFileName) }
        dao.trimToLimit(limit)
    }

    suspend fun dropAudioBeyond(limit: Int): Int {
        if (limit <= 0) return 0
        val targets =
            dao.entriesWithAudioBeyond(limit)
                .filterNot { TranscriptionPipeline.isUntranscribedHistoryText(it.text) }
        targets.forEach { entry ->
            dao.clearAudioFileName(entry.id)
            deleteAudio(entry.audioFileName)
        }
        return targets.size
    }

    fun audioUsage(): AudioUsage {
        val dir = audioDir ?: return AudioUsage(0, 0L)
        val files = runCatching { dir.listFiles() }.getOrNull()?.filter { it.isFile } ?: return AudioUsage(0, 0L)
        return AudioUsage(files.size, files.sumOf { it.length() })
    }

    fun audioFile(entry: HistoryEntry): File? {
        val dir = audioDir ?: return null
        val name = entry.audioFileName ?: return null
        return File(dir, name).takeIf { it.isFile }
    }

    fun readAudio(entry: HistoryEntry): ByteArray? {
        val dir = audioDir ?: return null
        val name = entry.audioFileName ?: return null
        return runCatching { File(dir, name).takeIf { it.isFile }?.readBytes() }.getOrNull()
    }

    suspend fun updateText(
        id: Long,
        text: String,
        model: String? = null,
    ): Boolean =
        inTransaction {
            val before = dao.getById(id) ?: return@inTransaction false

            dao.updateText(
                id,
                text,
                model,
                before.revisions.plusCapped(
                    Revision(before.text, before.model, before.transcribedAt ?: before.timestamp),
                ),
                transcribedAt = System.currentTimeMillis(),
            ) > 0
        }

    suspend fun delete(id: Long) {
        deleteAudio(dao.getById(id)?.audioFileName)
        dao.delete(id)
    }

    suspend fun clear() {
        deleteAllAudioFiles()
        dao.deleteAll()
        runCatching { compactAfterClear?.invoke() }
    }

    suspend fun sweepOrphanAudio(): Int {
        val dir = audioDir ?: return 0
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        if (files.isEmpty()) return 0
        val referenced = dao.allAudioFileNames().toSet()
        val cutoff = System.currentTimeMillis() - ORPHAN_GRACE_MS
        return files.count { f ->
            f.name !in referenced &&
                f.lastModified() < cutoff &&
                runCatching { f.delete() }.getOrDefault(false)
        }
    }

    private fun saveAudio(wavBytes: ByteArray?): String? {
        if (wavBytes == null || wavBytes.isEmpty()) return null
        val dir = audioDir ?: return null
        return runCatching {
            dir.mkdirs()
            val name = "rec-${System.currentTimeMillis()}-${(0..999_999).random()}.wav"
            File(dir, name).writeBytes(wavBytes)
            name
        }.getOrNull()
    }

    private fun deleteAudio(fileName: String?) {
        if (fileName == null) return
        val dir = audioDir ?: return

        runCatching { File(dir, fileName).delete() }
    }

    private fun deleteAllAudioFiles() {
        val dir = audioDir ?: return
        runCatching { dir.listFiles() }.getOrNull()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val ORPHAN_GRACE_MS = 5 * 60 * 1000L
    }
}

data class AudioUsage(
    val count: Int,
    val bytes: Long,
) {
    fun label(): String =
        when {
            count == 0 -> "スマホに残っている録音はありません"
            bytes < BYTES_PER_MB -> "スマホに残っている録音: $count 件・1 MB 未満"
            else -> "スマホに残っている録音: $count 件・約 ${(bytes + BYTES_PER_MB / 2) / BYTES_PER_MB} MB"
        }

    private companion object {
        const val BYTES_PER_MB = 1_000_000L
    }
}
