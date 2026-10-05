package net.anonen.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.asr.LocalModelManager
import net.anonen.app.asr.ModelStatsStore
import net.anonen.app.cloud.AnonenCloudAuth
import net.anonen.app.cloud.AnonenCloudClient
import net.anonen.app.cloud.AnonenCloudModels
import net.anonen.app.cloud.AnonenCloudUsage
import net.anonen.app.cloud.EntitlementManager
import net.anonen.app.cloud.RecordingLimits
import net.anonen.app.cloud.TranscribeCancelController
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.cloud.sealed.EnclaveAttestation
import net.anonen.app.cloud.sealed.EnclaveKeyProvider
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.NetworkStatus
import net.anonen.app.core.UserNoticeStore
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.history.AnonenDatabase
import net.anonen.app.history.HistoryRepository
import net.anonen.app.history.HistoryTrash
import net.anonen.app.history.Retranscriber
import net.anonen.app.settings.SecureStore
import net.anonen.app.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class AnonenApp : Application() {
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }
    val secureStore: SecureStore by lazy { SecureStore(this) }
    val historyRepository: HistoryRepository by lazy {
        val db = AnonenDatabase.get(this)
        HistoryRepository(
            db.historyDao(),
            audioDir = File(filesDir, "history_audio"),
            compactAfterClear = {
                withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("VACUUM") }
            },
            inTransaction = { block -> db.withTransaction { block() } },
        )
    }

    val retranscriber: Retranscriber by lazy { Retranscriber(this) }

    val historyTrash: HistoryTrash by lazy {
        HistoryTrash(scope = appScope, delete = {
                id ->
            historyRepository.delete(id)
        })
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    val localModelManager: LocalModelManager by lazy { LocalModelManager(this, httpClient) }
    val modelStats: ModelStatsStore by lazy { ModelStatsStore(this) }
    val cloudAuth: AnonenCloudAuth by lazy {
        AnonenCloudAuth(
            supabaseUrl = BuildConfig.ANONEN_SUPABASE_URL,
            anonKey = BuildConfig.ANONEN_SUPABASE_ANON_KEY,
            secrets = secureStore.prefs,
            client = httpClient,
            isOnline = { NetworkStatus.isOnline(this) },
            onSessionEstablished = { entitlement.onNewSession() },
            onSessionInvalidated = { entitlement.onLogout() },
        )
    }

    private val clientId: String get() = "android/${BuildConfig.VERSION_NAME}"

    val cloudClient: AnonenCloudClient by lazy {
        AnonenCloudClient(
            baseUrl = BuildConfig.ANONEN_CLOUD_URL,
            client = httpClient,
            clientId = clientId,
            plainUpload = DevFeaturesProvider.instance.plainUpload(),
        )
    }
    val cloudModels: AnonenCloudModels by lazy { AnonenCloudModels(cloudClient, SystemClock::elapsedRealtime) }

    val enclaveKeys: EnclaveKeyProvider by lazy {
        EnclaveKeyProvider(
            baseUrl = BuildConfig.ANONEN_CLOUD_URL,
            client = httpClient,
            acceptedImageDigests =
                EnclaveAttestation.parseAcceptedDigests(BuildConfig.ANONEN_ACCEPTED_IMAGE_DIGESTS),
            clientId = clientId,
        )
    }

    val entitlement: EntitlementManager by lazy {
        EntitlementManager(cloudAuth, secureStore.prefs)
    }
    val cloudUsage: AnonenCloudUsage by lazy {
        AnonenCloudUsage(
            cloudClient,
            cloudAuth,
            onAccount = { account, user ->
                entitlement.recordSubscription(account.subscriptionStatus, user)
            },
            onNoSubscription = { entitlement.onNoSubscription() },
            onCheckFailed = { entitlement.onCheckFailed() },
            recordingLimits = recordingLimits,
        )
    }

    val recordingLimits: RecordingLimits by lazy { RecordingLimits() }

    val transcribeCancel: TranscribeCancelController by lazy { TranscribeCancelController() }

    val userNotices: UserNoticeStore by lazy {
        UserNoticeStore(File(filesDir, "user_notices.json"))
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        DevFeaturesProvider.instance.onAppCreate(this)

        appScope.launch {
            val removed = runCatching { historyRepository.sweepOrphanAudio() }.getOrDefault(0)
            if (removed > 0) DiagnosticsLog.log("履歴: 参照の無い録音を $removed 件片づけた")
        }
    }

    suspend fun completeSignInAndRefresh() {
        runCatching { cloudModels.refresh(force = true) }
        ensureInitialCloudModel()
        withContext(Dispatchers.IO) { runCatching { cloudUsage.fetch() } }

        entitlement.onCheckFinished()
    }

    suspend fun ensureInitialCloudModel() {
        val current = settingsRepository.current()
        if (!current.showsInitialModelProposal) return
        val first = cloudModels.models.value.firstOrNull() ?: return
        if (current.needsDataPolicyDisclosure(first.dataPolicy().fingerprint)) return
        settingsRepository.update { s ->
            if (s.showsInitialModelProposal) s.withCloudModelSelected(first.id) else s
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            logThrowable("crash", e)
            previous?.uncaughtException(thread, e)
        }
    }

    fun logThrowable(
        tag: String,
        e: Throwable,
    ) {
        runCatching {
            val time =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())

            File(filesDir, CRASH_LOG_NAME)
                .writeText("[$tag] v${BuildConfig.VERSION_NAME} $time\n${Log.getStackTraceString(e)}")
        }

        runCatching {
            val where = e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
            DiagnosticsLog.log("内部エラー [$tag] ${e.javaClass.simpleName} @ $where")
        }
    }

    companion object {
        const val CRASH_LOG_NAME = "crash.log"

        fun from(context: Context): AnonenApp = context.applicationContext as AnonenApp
    }
}
