package net.anonen.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.anonen.app.core.DiagnosticsLog
import java.io.File
import java.security.KeyStore

class SecureStore(context: Context) {
    val prefs: SharedPreferences =
        File(context.noBackupFilesDir, FAILED_MARK).let { mark ->
            openWithRecovery(
                open = { open(context) },
                reset = { reset(context) },
                fallback = { MemoryOnlyPrefs() },
                failedLastLaunch = { mark.exists() },
                markFailed = { failed -> runCatching { if (failed) mark.createNewFile() else mark.delete() } },
                pause = { Thread.sleep(RETRY_PAUSE_MS) },
                log = { DiagnosticsLog.log(it) },
            )
        }

    companion object {
        private const val FILE_NAME = "handy_secrets"

        private const val FAILED_MARK = "secure_store_open_failed"

        private const val RETRY_PAUSE_MS = 500L

        private fun open(context: Context): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun reset(context: Context) {
            context.deleteSharedPreferences(FILE_NAME)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }

        internal fun <T> openWithRecovery(
            open: () -> T,
            reset: () -> Unit,
            fallback: () -> T,
            failedLastLaunch: () -> Boolean,
            markFailed: (Boolean) -> Unit,
            pause: () -> Unit,
            log: (String) -> Unit,
        ): T {
            for (attempt in 1..2) {
                try {
                    return open().also { markFailed(false) }
                } catch (e: Exception) {
                    log("暗号化の保存庫を開けない（${e.javaClass.simpleName}・$attempt 回目）")
                }
                if (attempt == 1) pause()
            }
            if (!failedLastLaunch()) {
                markFailed(true)
                log("この起動はメモリの中だけで動かす（消さずに、次の起動でもう一度開く）")
                return fallback()
            }
            log("前の起動でも開けなかった → 作り直す")
            runCatching { reset() }.onFailure { log("保存庫を消せない（${it.javaClass.simpleName}）") }
            try {
                return open().also { markFailed(false) }
            } catch (e: Exception) {
                log("作り直しても開けない（${e.javaClass.simpleName}）→ メモリの中だけで動かす")
            }
            return fallback()
        }
    }
}

internal class MemoryOnlyPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(map)

    @Synchronized
    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = map[key] as? String ?: defValue

    @Synchronized
    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    @Synchronized
    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = map[key] as? Int ?: defValue

    @Synchronized
    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = map[key] as? Long ?: defValue

    @Synchronized
    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = map[key] as? Float ?: defValue

    @Synchronized
    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = map[key] as? Boolean ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    @Synchronized
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.add(listener)
    }

    @Synchronized
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private fun commitChanges(
        changes: Map<String, Any?>,
        clear: Boolean,
    ) {
        val notify: List<SharedPreferences.OnSharedPreferenceChangeListener>
        synchronized(this) {
            if (clear) map.clear()
            for ((k, v) in changes) if (v == null) map.remove(k) else map[k] = v
            notify = listeners.toList()
        }
        for (k in changes.keys) for (l in notify) l.onSharedPreferenceChanged(this, k)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?,
        ) = apply { changes[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = apply { changes[key] = values?.toSet() }

        override fun putInt(
            key: String,
            value: Int,
        ) = apply { changes[key] = value }

        override fun putLong(
            key: String,
            value: Long,
        ) = apply { changes[key] = value }

        override fun putFloat(
            key: String,
            value: Float,
        ) = apply { changes[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = apply { changes[key] = value }

        override fun remove(key: String) = apply { changes[key] = null }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            commitChanges(changes, clear)
            return true
        }

        override fun apply() {
            commitChanges(changes, clear)
        }
    }
}
