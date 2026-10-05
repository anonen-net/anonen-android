package net.anonen.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.anonen.app.AnonenApp
import net.anonen.app.R
import net.anonen.app.cloud.AnonenCloudModels
import net.anonen.app.cloud.CloudModelInfo
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.ModelIds
import net.anonen.app.settings.AnonenSettings

internal sealed interface OneTapSwitch {
    data class Switch(val model: CloudModelInfo) : OneTapSwitch

    object CatalogUnavailable : OneTapSwitch

    data class KeepCurrent(val why: Refused) : OneTapSwitch

    data class OfferOther(val model: CloudModelInfo, val why: Refused) : OneTapSwitch

    data class Deselect(val why: Refused) : OneTapSwitch

    enum class Refused {
        TARGET_RETIRED,

        NEEDS_DISCLOSURE,

        NOT_PREVIOUSLY_SELECTED,
    }
}

internal fun decideOneTapSwitch(
    requestedId: String,
    settings: AnonenSettings,
    catalog: List<CloudModelInfo>,
): OneTapSwitch {
    if (catalog.isEmpty()) return OneTapSwitch.CatalogUnavailable
    val eligible = { model: CloudModelInfo -> settings.allowsOneTapSwitchTo(model.id, model.dataPolicy().fingerprint) }

    val requested = catalog.firstOrNull { it.id == requestedId }
    if (requested != null && eligible(requested)) return OneTapSwitch.Switch(requested)

    val why =
        when {
            requested == null -> OneTapSwitch.Refused.TARGET_RETIRED
            settings.needsDataPolicyDisclosure(
                requested.dataPolicy().fingerprint,
            ) -> OneTapSwitch.Refused.NEEDS_DISCLOSURE
            else -> OneTapSwitch.Refused.NOT_PREVIOUSLY_SELECTED
        }
    if (!AnonenCloudModels.isSelectionRetired(settings.selectedModel, catalog)) {
        return OneTapSwitch.KeepCurrent(why)
    }
    val other =
        settings.previouslySelectedCloudModels.firstNotNullOfOrNull { id ->
            catalog.firstOrNull { it.id == id }?.takeIf(eligible)
        }
    return if (other != null) OneTapSwitch.OfferOther(other, why) else OneTapSwitch.Deselect(why)
}

internal enum class SelectionAfter {
    USABLE,

    THE_TARGET_ITSELF,

    NONE,
}

internal fun selectionAfterKeeping(
    requestedId: String,
    settings: AnonenSettings,
): SelectionAfter =
    when {
        !settings.hasModelSelected -> SelectionAfter.NONE
        settings.selectedModel == ModelIds.ANONEN_CLOUD_PREFIX + requestedId -> SelectionAfter.THE_TARGET_ITSELF
        else -> SelectionAfter.USABLE
    }

internal data class NoticePart(
    val text: Int,
    val name: String? = null,
)

internal fun notSwitchedNotice(
    decision: OneTapSwitch,
    after: SelectionAfter,
    requestedName: String,
    catalogConfirmedEmpty: Boolean,
): List<NoticePart> {
    fun why(refused: OneTapSwitch.Refused) =
        NoticePart(
            when (refused) {
                OneTapSwitch.Refused.TARGET_RETIRED -> R.string.notice_model_switch_why_retired
                OneTapSwitch.Refused.NEEDS_DISCLOSURE -> R.string.notice_model_switch_why_needs_disclosure
                OneTapSwitch.Refused.NOT_PREVIOUSLY_SELECTED -> R.string.notice_model_switch_why_not_previous
            },
            requestedName,
        )

    fun howToChoose(refused: OneTapSwitch.Refused) =
        when (refused) {
            OneTapSwitch.Refused.NEEDS_DISCLOSURE ->
                listOf(NoticePart(R.string.notice_model_switch_how_to_choose, requestedName))
            OneTapSwitch.Refused.NOT_PREVIOUSLY_SELECTED ->
                listOf(NoticePart(R.string.notice_model_switch_how_to_choose_plain, requestedName))
            OneTapSwitch.Refused.TARGET_RETIRED -> emptyList()
        }

    return when (decision) {
        is OneTapSwitch.Switch -> emptyList()
        OneTapSwitch.CatalogUnavailable ->
            listOf(
                NoticePart(
                    if (catalogConfirmedEmpty) {
                        R.string.notice_model_switch_catalog_empty
                    } else {
                        R.string.notice_model_switch_catalog_unavailable
                    },
                ),
            )
        is OneTapSwitch.KeepCurrent ->
            when {
                after == SelectionAfter.THE_TARGET_ITSELF && decision.why == OneTapSwitch.Refused.NEEDS_DISCLOSURE ->
                    listOf(NoticePart(R.string.notice_model_switch_target_is_current, requestedName))
                after == SelectionAfter.THE_TARGET_ITSELF ->
                    listOf(NoticePart(R.string.notice_model_switch_target_is_current_kept, requestedName))
                after == SelectionAfter.NONE ->
                    listOf(why(decision.why), NoticePart(R.string.notice_model_switch_then_none))
                else ->
                    listOf(why(decision.why), NoticePart(R.string.notice_model_switch_then_kept)) +
                        howToChoose(decision.why)
            }
        is OneTapSwitch.OfferOther ->
            listOf(
                why(decision.why),
                NoticePart(
                    R.string.notice_model_switch_then_offer,
                    decision.model.displayName.ifBlank { decision.model.id },
                ),
            ) + howToChoose(decision.why)
        is OneTapSwitch.Deselect ->
            listOf(why(decision.why), NoticePart(R.string.notice_model_switch_then_deselected))
    }
}

internal suspend fun CoroutineScope.confirmCatalogAfterTap(
    models: AnonenCloudModels,
    waitMs: Long,
): Boolean {
    val tappedAtMs = models.now()
    val fetch = async { runCatching { models.refresh(force = models.models.value.isEmpty()) } }
    withTimeoutOrNull(waitMs) { fetch.await() }
    return models.models.value.isEmpty() && models.loadedSince(tappedAtMs)
}

class CloudModelSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SWITCH) return
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return
        val displayName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
        val app = AnonenApp.from(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val catalogConfirmedEmpty = confirmCatalogAfterTap(app.cloudModels, CATALOG_WAIT_MS)
                val notifier = AnonenNotifier(context)
                val settings = app.settingsRepository.current()
                val decision = decideOneTapSwitch(modelId, settings, app.cloudModels.models.value)

                val text =
                    notSwitchedNotice(
                        decision,
                        selectionAfterKeeping(modelId, settings),
                        displayName,
                        catalogConfirmedEmpty,
                    ).joinToString("") { part ->
                        if (part.name != null) context.getString(part.text, part.name) else context.getString(part.text)
                    }
                when (decision) {
                    is OneTapSwitch.Switch -> {
                        app.settingsRepository.update { it.withCloudModelSelected(decision.model.id) }
                        DiagnosticsLog.log("過去に使ったモデルへ切替: $modelId（通知アクション）")

                        notifier.notifyModelSwitched(displayName)
                        app.userNotices.post(context.getString(R.string.notice_model_switched, displayName))
                    }
                    OneTapSwitch.CatalogUnavailable -> {
                        notifier.notifyModelNotSwitched(text)
                        app.userNotices.post(text)
                        DiagnosticsLog.log("通知からの切替を見送り: $modelId（一覧を確かめられない）")
                    }
                    is OneTapSwitch.KeepCurrent -> {
                        notifier.notifyModelNotSwitched(text)
                        app.userNotices.post(text)
                        DiagnosticsLog.log("通知からの切替を見送り: $modelId（${decision.why}・選択は変えない）")
                    }
                    is OneTapSwitch.OfferOther -> {
                        val name = decision.model.displayName.ifBlank { decision.model.id }

                        notifier.notifyModelRetired(
                            switchTargetId = decision.model.id,
                            switchTargetName = name,
                            detail = text,
                        )
                        app.userNotices.post(text)
                        DiagnosticsLog.log("通知からの切替を見送り: $modelId（${decision.why}）→ ${decision.model.id} を提示")
                    }
                    is OneTapSwitch.Deselect -> {
                        app.settingsRepository.update { it.withNoModelSelected() }
                        notifier.notifyNoModelSelected(detail = text)
                        app.userNotices.post(text)
                        DiagnosticsLog.log(
                            "通知からの切替を見送り: $modelId（${decision.why}）・選択中も提供終了で、切り替えられる過去選択なし",
                        )
                    }
                }
            } catch (e: Exception) {
                app.logThrowable("model-switch", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SWITCH = "net.anonen.app.action.SWITCH_CLOUD_MODEL"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_MODEL_NAME = "model_name"

        private const val CATALOG_WAIT_MS = 5_000L

        fun intent(
            context: Context,
            modelId: String,
            displayName: String?,
        ): Intent =
            Intent(context, CloudModelSwitchReceiver::class.java)
                .setAction(ACTION_SWITCH)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_MODEL_NAME, displayName)
    }
}
