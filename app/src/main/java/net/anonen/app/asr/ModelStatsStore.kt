package net.anonen.app.asr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.modelStatsDataStore by preferencesDataStore(name = "model_stats")

@Serializable
data class StatSample(
    val ms: Long,
    val audioMs: Long,
)

class ModelStatsStore(context: Context) {
    private val dataStore = context.applicationContext.modelStatsDataStore

    val stats: Flow<Map<String, List<StatSample>>> =
        dataStore.data.map { p -> p[KEY]?.let { decode(it) } ?: emptyMap() }

    suspend fun record(
        modelId: String,
        asrMs: Long,
        audioMs: Long,
    ) {
        if (modelId.isBlank() || asrMs <= 0 || audioMs <= 0) return
        dataStore.edit { p ->
            p.remove(LEGACY_KEY)
            val current = p[KEY]?.let { decode(it) } ?: emptyMap()
            val updated = (listOf(StatSample(asrMs, audioMs)) + current[modelId].orEmpty()).take(KEEP)
            p[KEY] = json.encodeToString(serializer, current + (modelId to updated))
        }
    }

    suspend fun clear() {
        dataStore.edit { p ->
            p.remove(KEY)
            p.remove(LEGACY_KEY)
        }
    }

    private fun decode(raw: String): Map<String, List<StatSample>> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())

    companion object {
        private const val KEEP = 20
        private val KEY = stringPreferencesKey("stats_v2_json")

        private val LEGACY_KEY = stringPreferencesKey("stats_json")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer =
            MapSerializer(String.serializer(), ListSerializer(StatSample.serializer()))

        fun medianMs(samples: List<Long>): Long? = median(samples)

        private fun <T : Comparable<T>> median(values: List<T>): T? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        const val PRIOR_WEIGHT = 5

        const val MIN_AUDIO_MS = 2000L

        fun perAudioSecond(samples: List<StatSample>): List<Double> =
            samples.filter { it.audioMs >= MIN_AUDIO_MS && it.ms > 0 }.map { it.ms / (it.audioMs / 1000.0) }

        fun blendedSpeedScores(
            stats: Map<String, List<Double>>,
            serverScores: Map<String, Float>,
        ): Map<String, Float> {
            class Row(val id: String, val s: Double, val n: Int, val l: Double)

            val rows =
                serverScores.mapNotNull { (id, s) ->

                    if (!(s > 0f)) return@mapNotNull null
                    val samples = stats[id].orEmpty()
                    val l = median(samples)
                    Row(id, s.toDouble(), if (l == null) 0 else samples.size, l ?: 0.0)
                }
            val measured = rows.filter { it.n > 0 }
            val total = measured.sumOf { it.n }
            if (total == 0) return emptyMap()

            val c = measured.sumOf { it.n * it.l * it.s } / total
            val blended =
                rows.associate { r ->
                    r.id to (PRIOR_WEIGHT * (c / r.s) + r.n * r.l) / (PRIOR_WEIGHT + r.n)
                }
            val fastest = blended.values.min()
            return blended.mapValues { (_, ms) -> (fastest / ms).coerceIn(0.01, 1.0).toFloat() }
        }
    }
}
