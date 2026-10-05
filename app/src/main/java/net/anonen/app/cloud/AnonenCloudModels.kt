package net.anonen.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.ModelIds
import java.util.concurrent.atomic.AtomicReference

class AnonenCloudModels(
    private val client: AnonenCloudClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _models = MutableStateFlow<List<CloudModelInfo>>(emptyList())
    val models: StateFlow<List<CloudModelInfo>> = _models.asStateFlow()

    enum class Fetch {
        NOT_YET,

        LOADED,

        FAILED,
    }

    private val _lastFetch = MutableStateFlow(Fetch.NOT_YET)
    val lastFetch: StateFlow<Fetch> = _lastFetch.asStateFlow()

    @Volatile
    private var lastFetchMs = 0L
    private val fetchMutex = Mutex()

    private val lastModelsVersion = AtomicReference<String?>(null)

    fun noteModelsVersion(version: String): Boolean = lastModelsVersion.getAndSet(version) != version

    suspend fun refresh(force: Boolean = false): Fetch? {
        fetchMutex.withLock {
            val fresh = _lastFetch.value == Fetch.LOADED && clock() - lastFetchMs < CACHE_TTL_MS
            if (!force && fresh) return null
            val fetched =
                withContext(Dispatchers.IO) {
                    try {
                        client.fetchModels()
                    } catch (e: Exception) {
                        DiagnosticsLog.log("Cloud モデル取得失敗: ${e.message}")
                        null
                    }
                }
            if (fetched != null) {
                _models.value = fetched
                lastFetchMs = clock()
                _lastFetch.value = Fetch.LOADED
                DiagnosticsLog.log("Cloud モデル ${fetched.size} 件取得")
                return Fetch.LOADED
            }
            _lastFetch.value = Fetch.FAILED
            return Fetch.FAILED
        }
    }

    fun now(): Long = clock()

    fun loadedSince(sinceMs: Long): Boolean = lastFetchMs != 0L && lastFetchMs >= sinceMs

    fun displayName(cloudModelId: String): String? = _models.value.firstOrNull { it.id == cloudModelId }?.displayName

    fun modelInfo(cloudModelId: String): CloudModelInfo? = _models.value.firstOrNull { it.id == cloudModelId }

    fun recommendedModel(): CloudModelInfo? = _models.value.firstOrNull { it.isRecommended }

    fun firstAvailablePreviouslySelected(
        orderedPreviousIds: List<String>,
        eligible: (CloudModelInfo) -> Boolean = { true },
    ): CloudModelInfo? =
        orderedPreviousIds.firstNotNullOfOrNull { id ->
            _models.value.firstOrNull { it.id == id }?.takeIf(eligible)
        }

    fun isSelectionRetired(selectedModel: String): Boolean = isSelectionRetired(selectedModel, _models.value)

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        fun emptyCatalogMessage(lastFetch: Fetch): String =
            when (lastFetch) {
                Fetch.NOT_YET -> "一覧を読み込んでいます…"
                Fetch.FAILED -> "一覧を読み込めませんでした"
                Fetch.LOADED -> "今はネットで使えるモデルがありません"
            }

        fun isSelectionRetired(
            selectedModel: String,
            models: List<CloudModelInfo>,
        ): Boolean {
            if (!selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) return false
            if (models.isEmpty()) return false
            val cloudId = selectedModel.removePrefix(ModelIds.ANONEN_CLOUD_PREFIX)
            if (cloudId.isBlank()) return false
            return models.none { it.id == cloudId }
        }
    }
}
