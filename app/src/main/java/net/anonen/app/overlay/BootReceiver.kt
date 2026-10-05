package net.anonen.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.anonen.app.AnonenApp
import net.anonen.app.core.DiagnosticsLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        DiagnosticsLog.log("復帰レシーバ受信: ${action?.substringAfterLast('.')}")

        if (!Settings.canDrawOverlays(context)) {
            DiagnosticsLog.log("復帰スキップ: オーバーレイ権限なし")
            return
        }

        val app = AnonenApp.from(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val autoStart =
                    withTimeoutOrNull(BOOT_READ_TIMEOUT_MS) {
                        app.settingsRepository.current().autoStartOnBoot
                    } ?: false
                DiagnosticsLog.log("復帰判定 autoStart=$autoStart")
                if (autoStart && Settings.canDrawOverlays(context)) {
                    FloatingButtonService.start(context)
                    DiagnosticsLog.log("復帰起動を要求")
                }
            } catch (e: Exception) {
                DiagnosticsLog.log("復帰失敗: ${e.javaClass.simpleName}")
                app.logThrowable("boot-receiver", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BOOT_READ_TIMEOUT_MS = 8_000L
    }
}
