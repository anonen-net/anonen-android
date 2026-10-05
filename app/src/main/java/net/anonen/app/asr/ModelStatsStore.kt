package net.anonen.app.asr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.anonen.app.core.ModelIds

private val Context.modelStatsDataStore by preferencesDataStore(name = "model_stats")

class ModelStatsStore(context: Context) {
    private val dataStore = context.applicationContext.modelStatsDataStore

    val stats: Flow<Map<String, List<Long>>> =
        dataStore.data.map { p -> p[KEY]?.let { decode(it) } ?: emptyMap() }

    suspend fun record(
        modelId: String,
        asrMs: Long,
    ) {
        if (modelId.isBlank() || asrMs <= 0) return
        dataStore.edit { p ->
            val current = p[KEY]?.let { decode(it) } ?: emptyMap()
            val updated = (listOf(asrMs) + current[modelId].orEmpty()).take(KEEP)
            p[KEY] = json.encodeToString(serializer, current + (modelId to updated))
        }
    }

    suspend fun clear() {
        dataStore.edit { p -> p.remove(KEY) }
    }

    private fun decode(raw: String): Map<String, List<Long>> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())

    companion object {
        private const val KEEP = 20
        private val KEY = stringPreferencesKey("stats_json")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer =
            MapSerializer(String.serializer(), ListSerializer(Long.serializer()))

        fun medianMs(samples: List<Long>): Long? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            return sorted[sorted.size / 2]
        }

        const val MIN_FOR_RELATIVE = 3

        fun relativeSpeedScores(stats: Map<String, List<Long>>): Map<String, Float> {
            val medians =
                stats.mapNotNull { (modelId, samples) ->
                    if (!modelId.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) return@mapNotNull null
                    if (samples.size < MIN_FOR_RELATIVE) return@mapNotNull null
                    val m = medianMs(samples) ?: return@mapNotNull null
                    if (m <= 0) null else modelId to m
                }.toMap()
            if (medians.size < 2) return emptyMap()

            val fastest = medians.values.min()
            return medians.mapValues { (_, ms) ->
                (fastest.toFloat() / ms.toFloat()).coerceIn(0.01f, 1f)
            }
        }
    }
}
