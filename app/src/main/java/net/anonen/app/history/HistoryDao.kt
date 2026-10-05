package net.anonen.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntry?

    @Query(
        "UPDATE history SET revisions = :revisions, " +
            "text = :text, model = coalesce(:model, model), " +
            "transcribedAt = :transcribedAt WHERE id = :id",
    )
    suspend fun updateText(
        id: Long,
        text: String,
        model: String?,
        revisions: List<Revision>,
        transcribedAt: Long,
    ): Int

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT audioFileName FROM history WHERE audioFileName IS NOT NULL")
    suspend fun allAudioFileNames(): List<String>

    @Query(
        "SELECT * FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun entriesBeyondLimit(limit: Int): List<HistoryEntry>

    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun trimToLimit(limit: Int)

    @Query(
        "SELECT * FROM history WHERE audioFileName IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun entriesWithAudioBeyond(limit: Int): List<HistoryEntry>

    @Query("UPDATE history SET audioFileName = NULL WHERE id = :id")
    suspend fun clearAudioFileName(id: Long)
}
