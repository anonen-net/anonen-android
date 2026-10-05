package net.anonen.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val text: String,
    val durationMs: Long,
    val audioFileName: String? = null,
    val model: String? = null,
    val revisions: List<Revision> = emptyList(),
    val transcribedAt: Long? = null,
)

data class Revision(
    val text: String,
    val model: String? = null,
    val transcribedAt: Long? = null,
)

const val MAX_REVISIONS = 10

object RevisionConverters {
    @TypeConverter
    fun fromJson(raw: String?): List<Revision> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Revision(
                    text = o.optString("text"),
                    model = if (o.isNull("model")) null else o.optString("model"),
                    transcribedAt = if (o.isNull("transcribedAt")) null else o.getLong("transcribedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun toJson(list: List<Revision>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("text", r.text)
                    put("model", r.model ?: JSONObject.NULL)
                    put("transcribedAt", r.transcribedAt ?: JSONObject.NULL)
                },
            )
        }
        return arr.toString()
    }
}

fun List<Revision>.plusCapped(revision: Revision): List<Revision> = (this + revision).takeLast(MAX_REVISIONS)
