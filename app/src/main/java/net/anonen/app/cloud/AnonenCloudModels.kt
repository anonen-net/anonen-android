package net.anonen.app.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.ModelIds
import net.anonen.app.settings.AnonenSettings
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

    suspend fun refresh(
        force: Boolean = false,
        maxAgeMs: Long = CACHE_TTL_MS,
    ): Fetch? {
        fetchMutex.withLock {
            val fresh = _lastFetch.value == Fetch.LOADED && clock() - lastFetchMs < maxAgeMs
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

    private val background =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e ->
                    DiagnosticsLog.log("Cloud モデルの取り直しが落ちた: ${e.javaClass.simpleName}")
                },
        )

    private var backgroundFetch: Deferred<Fetch?>? = null

    suspend fun refreshWithin(
        maxAgeMs: Long,
        waitMs: Long,
    ) {
        withTimeoutOrNull(waitMs) { consentFetch(maxAgeMs) }
    }

    fun startConsentRefresh() {
        background.launch { consentFetch(CONSENT_MAX_AGE_MS) }
    }

    private suspend fun consentFetch(maxAgeMs: Long) {
        val (fetch, reused) = sharedFetch(maxAgeMs)
        if (fetch.awaitQuietly() == Fetch.FAILED && reused) sharedFetch(maxAgeMs).first.awaitQuietly()
    }

    private suspend fun Deferred<Fetch?>.awaitQuietly(): Fetch? =
        try {
            await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticsLog.log("Cloud モデルの取り直しが落ちた: ${e.javaClass.simpleName}")
            Fetch.FAILED
        }

    private fun sharedFetch(maxAgeMs: Long): Pair<Deferred<Fetch?>, Boolean> =
        synchronized(this) {
            backgroundFetch?.takeIf { it.isActive }?.let { it to true }
                ?: (background.async { refresh(maxAgeMs = maxAgeMs) }.also { backgroundFetch = it } to false)
        }

    suspend fun refreshForConsent() = refreshWithin(CONSENT_MAX_AGE_MS, CONSENT_CATALOG_WAIT_MS)

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

    fun isLoadedButEmpty(): Boolean = _lastFetch.value == Fetch.LOADED && _models.value.isEmpty()

    fun blocksStartForEmptyCatalog(selectedModel: String): Boolean =
        selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX) && isLoadedButEmpty()

    fun selectedModelNeedingDisclosure(settings: AnonenSettings): CloudModelInfo? =
        selectedModelNeedingDisclosure(settings, _models.value)

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        const val CONSENT_MAX_AGE_MS = 60 * 1000L

        const val CONSENT_CATALOG_WAIT_MS = 3_000L

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

        fun selectedModelNeedingDisclosure(
            settings: AnonenSettings,
            models: List<CloudModelInfo>,
        ): CloudModelInfo? {
            val fingerprints = models.associate { it.id to it.dataPolicy().fingerprint }
            val id = settings.selectedCloudModelNeedingDisclosure(fingerprints)
            return id?.let { needed -> models.firstOrNull { it.id == needed } }
        }
    }
}
