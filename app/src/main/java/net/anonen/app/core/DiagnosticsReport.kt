package net.anonen.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import net.anonen.app.BuildConfig
import net.anonen.app.cloud.AnonenCloudAuth
import net.anonen.app.cloud.CloudAccountStatus
import net.anonen.app.cloud.UsageSnapshot
import net.anonen.app.inject.AnonenAccessibilityService
import net.anonen.app.settings.AnonenSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsReport {
    private const val USER_ID_PREFIX = 8
    private const val LABEL_WIDTH = 10
    private const val HEADER = "==== ANONEN diagnostics ===="
    private const val FOOTER = "============================"

    const val CONTENTS =
        "入るもの: 日時と時間帯・アプリの版・スマホの機種・Android の版・許可の状態・使っているモデル・アプリの動き" +
            "（ログイン中はアカウント ID の先頭 8 文字・契約・使った量も）\n" +
            "入らないもの: メールアドレス・文字起こしした内容"

    data class Environment(
        val generatedAt: String,
        val versionName: String,
        val versionCode: Int,
        val buildType: String,
        val device: String,
        val androidRelease: String,
        val sdkInt: Int,
        val overlayGranted: Boolean,
        val micGranted: Boolean,
        val accessibilityConnected: Boolean,
        val selectedModel: String,
        val signedIn: Boolean,
        val userId: String?,
        val planName: String?,
        val subscriptionStatus: String?,
        val currentPeriodEnd: String?,
        val usage: UsageSnapshot?,
    )

    fun build(
        context: Context,
        settings: AnonenSettings?,
        auth: AnonenCloudAuth.AuthStatus,
        account: CloudAccountStatus?,
        usage: UsageSnapshot?,
    ): String = format(collect(context, settings, auth, account, usage), DiagnosticsLog.fullLogText())

    fun format(
        env: Environment,
        log: String,
    ): String {
        val rows = mutableListOf<String>()
        rows += row("generated", env.generatedAt)
        rows += row("app", "anonen ${env.versionName} (build ${env.versionCode}, ${env.buildType})")
        rows += row("device", env.device)
        rows += row("android", "${env.androidRelease} (API ${env.sdkInt})")
        rows +=
            row(
                "granted",
                "overlay=${yesNo(env.overlayGranted)} mic=${yesNo(env.micGranted)} " +
                    "accessibility=${yesNo(env.accessibilityConnected)}",
            )
        rows += row("model", "${env.selectedModel} (${modelKind(env.selectedModel)})")
        rows += accountRow(env)
        planRow(env)?.let { rows += it }
        usageRow(env.usage)?.let { rows += it }
        rows += row("log", if (log.isBlank()) "empty" else "full, oldest first")

        val header = (listOf(HEADER) + rows + FOOTER).joinToString("\n")

        return if (log.isBlank()) header else "$header\n$log"
    }

    private fun collect(
        context: Context,
        settings: AnonenSettings?,
        auth: AnonenCloudAuth.AuthStatus,
        account: CloudAccountStatus?,
        usage: UsageSnapshot?,
    ): Environment =
        Environment(
            generatedAt = timestamp(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            device = runCatching { "${Build.MANUFACTURER} ${Build.MODEL}" }.getOrDefault("unknown"),
            androidRelease = runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "unknown",
            sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0),
            overlayGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
            micGranted =
                runCatching {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false),
            accessibilityConnected = runCatching { AnonenAccessibilityService.isConnected }.getOrDefault(false),
            selectedModel = settings?.selectedModel ?: "unknown",
            signedIn = auth.signedIn,
            userId = auth.userId,
            planName = account?.planName,
            subscriptionStatus = account?.subscriptionStatus,
            currentPeriodEnd = account?.currentPeriodEnd,
            usage = usage,
        )

    private fun row(
        label: String,
        value: String,
    ): String = "${label.padEnd(LABEL_WIDTH)}: $value"

    private fun yesNo(granted: Boolean): String = if (granted) "yes" else "no"

    private fun modelKind(selectedModel: String): String =
        when {
            selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX) -> "cloud"
            ModelIds.isNoneSelected(selectedModel) -> "none selected"
            else -> "local"
        }

    private fun accountRow(env: Environment): String =
        if (!env.signedIn) {
            row("account", "signed out")
        } else {
            row("account", "signed in (id ${env.userId?.take(USER_ID_PREFIX) ?: "unavailable"})")
        }

    private fun planRow(env: Environment): String? {
        if (!env.signedIn) return null
        val period = env.currentPeriodEnd?.let { ", period end $it" } ?: ""
        return row(
            "plan",
            "${env.planName ?: "unknown"} (${env.subscriptionStatus ?: "unknown"}$period)",
        )
    }

    private fun usageRow(usage: UsageSnapshot?): String? {
        if (usage == null) return null
        val perRecording = usage.maxRequestS?.let { ", per rec. ${it}s max" } ?: ""
        return row(
            "usage",
            "week ${hours(usage.weekUsedS)}/${hours(usage.weekCapS)}h, " +
                "month ${hours(usage.monthUsedS)}/${hours(usage.monthCapS)}h$perRecording",
        )
    }

    private fun hours(seconds: Int): String = String.format(Locale.US, "%.1f", seconds / 3600.0)

    private fun timestamp(): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()) +
                " (${TimeZone.getDefault().id})"
        }.getOrDefault("unknown")
}
