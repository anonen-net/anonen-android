package net.anonen.app.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.anonen.app.AnonenApp
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.inject.AnonenAccessibilityService
import net.anonen.app.overlay.FloatingButtonService

private val BUTTON_SHAPE = RoundedCornerShape(4.dp)
private val BUTTON_BORDER = 2.dp

internal data class PermissionStates(
    val overlay: Boolean,
    val microphone: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean = true,
) {
    val canStart: Boolean get() = overlay && microphone
}

internal fun needsNotificationAsk(
    sdk: Int,
    granted: Boolean,
    asked: Boolean,
): Boolean = sdk >= 33 && !granted && !asked

internal fun needsButtonModeChoice(
    permissions: PermissionStates,
    useWithoutAccessibility: Boolean,
): Boolean = permissions.canStart && !permissions.accessibility && !useWithoutAccessibility

internal enum class HomePrimaryAction(
    val label: String,
) {
    GRANT_OVERLAY("重ねて表示を許可する"),
    GRANT_MICROPHONE("マイクを許可する"),
    CHOOSE_MODEL("モデルを選ぶ"),
    SHOW_BUTTON("ボタンを出す"),

    TRY_IT("試しに話す"),
}

internal fun homePrimaryAction(
    permissions: PermissionStates,
    hasModel: Boolean,
    running: Boolean,
): HomePrimaryAction =
    when {
        running -> if (hasModel) HomePrimaryAction.TRY_IT else HomePrimaryAction.CHOOSE_MODEL
        !permissions.overlay -> HomePrimaryAction.GRANT_OVERLAY
        !permissions.microphone -> HomePrimaryAction.GRANT_MICROPHONE
        !hasModel -> HomePrimaryAction.CHOOSE_MODEL
        else -> HomePrimaryAction.SHOW_BUTTON
    }

private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun readPermissions(context: Context): PermissionStates =
    PermissionStates(
        overlay = Settings.canDrawOverlays(context),
        microphone =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        accessibility = AnonenAccessibilityService.isConnected,
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

@Composable
internal fun HomeScreen(
    chooseModel: Boolean = false,
    onChooseModelHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = AnonenApp.from(context)
    val scope = rememberCoroutineScope()
    val settingsOrNull by app.settingsRepository.settings.collectAsState(initial = null)
    val settingsLoaded = settingsOrNull != null
    var refreshTick by remember { mutableIntStateOf(0) }
    var startRequested by remember { mutableStateOf(false) }

    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    var showButtonModeChoice by remember { mutableStateOf(false) }

    var showModelProposal by rememberSaveable { mutableStateOf(false) }
    val cloudModels by app.cloudModels.models.collectAsState()
    val scrollState = rememberScrollState()
    var modelSectionTop by remember { mutableIntStateOf(0) }
    var trySectionTop by remember { mutableIntStateOf(0) }

    var setupRunStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var lastLaunched by rememberSaveable { mutableStateOf<String?>(null) }

    var showOverlayCard by remember { mutableStateOf(false) }
    var showMicExplain by remember { mutableStateOf(false) }

    var overlayWatch by remember { mutableStateOf<Job?>(null) }
    val permissions = remember(refreshTick) { readPermissions(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    startRequested = false
                    overlayWatch?.cancel()
                    refreshTick++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshTick++
            if (!granted) {
                val activity = context.findActivity()
                val rationale =
                    activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                when (micDenialNext(rationale)) {
                    MicDenial.EXPLAIN -> showMicExplain = true
                    MicDenial.OPEN_SETTINGS ->
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                }
            }
        }

    fun startFloatingButton() {
        FloatingButtonService.start(context)
        startRequested = true
        refreshTick++
        scope.launch {
            app.settingsRepository.update { it.copy(autoStartOnBoot = true) }
        }
    }

    fun stopFloatingButton() {
        FloatingButtonService.stop(context)
        startRequested = false
        refreshTick++
        scope.launch {
            app.settingsRepository.update { it.copy(autoStartOnBoot = false) }
        }
    }

    var tryFocusNonce by remember { mutableIntStateOf(0) }

    fun openTryIt() {
        scope.launch { scrollState.animateScrollTo(trySectionTop) }
        tryFocusNonce++
    }

    fun showButton() {
        val chosen = settingsOrNull?.useWithoutAccessibility == true
        if (needsButtonModeChoice(permissions, chosen)) {
            showButtonModeChoice = true
        } else {
            startFloatingButton()
            if (permissions.accessibility) openTryIt()
        }
    }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick++
            scope.launch { app.settingsRepository.update { it.copy(notificationPermissionAsked = true) } }
            showButton()
        }

    val notificationRowLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick++
            scope.launch { app.settingsRepository.update { it.copy(notificationPermissionAsked = true) } }
        }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }

    fun openOverlaySettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    fun scrollToModels() {
        scope.launch { scrollState.animateScrollTo(modelSectionTop) }
    }

    fun watchOverlayGrant() {
        overlayWatch?.cancel()
        overlayWatch =
            scope.launch {
                repeat(OVERLAY_WATCH_TRIES) {
                    delay(OVERLAY_WATCH_INTERVAL_MS)
                    if (Settings.canDrawOverlays(context)) {
                        bringAppToFront(context)
                        return@launch
                    }
                }
            }
    }

    fun runPrimary(action: HomePrimaryAction) {
        val now = System.currentTimeMillis()
        if (action != HomePrimaryAction.TRY_IT && !setupRunActive(setupRunStartedAt, now)) setupRunStartedAt = now
        lastLaunched = action.name
        when (action) {
            HomePrimaryAction.GRANT_OVERLAY -> showOverlayCard = true
            HomePrimaryAction.GRANT_MICROPHONE -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            HomePrimaryAction.CHOOSE_MODEL ->
                if (settingsOrNull?.showsInitialModelProposal == true && cloudModels.isNotEmpty()) {
                    showModelProposal = true
                } else {
                    scrollToModels()
                }
            HomePrimaryAction.SHOW_BUTTON ->
                if (needsNotificationAsk(
                        Build.VERSION.SDK_INT,
                        permissions.notifications,
                        settingsOrNull?.notificationPermissionAsked == true,
                    )
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    showButton()
                }
            HomePrimaryAction.TRY_IT -> openTryIt()
        }
    }

    LaunchedEffect(permissions.accessibility, settingsOrNull?.startAfterAccessibility) {
        if (permissions.accessibility && settingsOrNull?.startAfterAccessibility == true) {
            app.settingsRepository.update { it.copy(startAfterAccessibility = false) }
            startFloatingButton()
            openTryIt()
        }
    }

    LaunchedEffect(chooseModel, settingsLoaded) {
        if (chooseModel && settingsLoaded) {
            onChooseModelHandled()
            runPrimary(HomePrimaryAction.CHOOSE_MODEL)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
    ) {
        Text(
            "あのねん",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            "ボタンを押して話すと、入力欄に文字が入ります。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val running = (FloatingButtonService.isRunning || startRequested) && refreshTick >= 0
        val hasModel = settingsOrNull?.hasModelSelected ?: true
        val action = homePrimaryAction(permissions, hasModel = hasModel, running = running)

        LaunchedEffect(action, settingsLoaded) {
            if (!settingsLoaded) return@LaunchedEffect
            val launched = lastLaunched?.let { name -> HomePrimaryAction.entries.firstOrNull { it.name == name } }
            autoAdvanceTo(launched, action, setupRunActive(setupRunStartedAt, System.currentTimeMillis()))
                ?.let { runPrimary(it) }
        }
        OutlinedButton(
            onClick = { runPrimary(action) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            border = BorderStroke(BUTTON_BORDER, cardBorderColor()),
            shape = BUTTON_SHAPE,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) { Text(action.label) }

        if (running) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { stopFloatingButton() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                border = BorderStroke(BUTTON_BORDER, cardBorderColor()),
                shape = BUTTON_SHAPE,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) { Text("ボタンをしまう") }
        }

        val setupComplete =
            permissions.overlay && permissions.microphone && permissions.accessibility && permissions.notifications
        if (!setupComplete) {
            Spacer(Modifier.height(12.dp))
            SettingsGroup("準備") {
                PermissionRow(
                    label = "他のアプリの上に重ねて表示",
                    required = true,
                    granted = permissions.overlay,
                ) { showOverlayCard = true }
                SettingDivider()
                PermissionRow(
                    label = "マイク",
                    required = true,
                    granted = permissions.microphone,
                ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                SettingDivider()
                PermissionRow(
                    label = "ユーザー補助（自動で入力）",
                    required = false,
                    granted = permissions.accessibility,
                ) { showAccessibilityDisclosure = true }
                SettingDivider()
                PermissionRow(
                    label = "通知",
                    required = false,
                    granted = permissions.notifications,
                ) {
                    if (needsNotificationAsk(
                            Build.VERSION.SDK_INT,
                            permissions.notifications,
                            settingsOrNull?.notificationPermissionAsked == true,
                        )
                    ) {
                        notificationRowLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings()
                    }
                }
            }
        }

        if (showButtonModeChoice) {
            ButtonModeChoiceDialog(
                onDismiss = { showButtonModeChoice = false },
                onEnableAccessibility = {
                    showButtonModeChoice = false
                    scope.launch { app.settingsRepository.update { it.copy(startAfterAccessibility = true) } }

                    showAccessibilityDisclosure = true
                },
                onUseWithout = {
                    showButtonModeChoice = false
                    scope.launch {
                        app.settingsRepository.update { it.copy(useWithoutAccessibility = true) }
                    }
                    startFloatingButton()
                },
            )
        }

        val proposal = cloudModels.firstOrNull()
        if (showModelProposal && proposal != null) {
            ModelDataPolicyDialog(
                model = proposal,
                mode = PolicyDialogMode.PROPOSE,
                onAccept = {
                    showModelProposal = false
                    scope.launch {
                        app.settingsRepository.update {
                            it.withDataPolicyAcknowledged(proposal.dataPolicy().fingerprint)
                                .withCloudModelSelected(proposal.id)
                        }
                    }
                },
                onDecline = {
                    showModelProposal = false
                    scope.launch { app.settingsRepository.update { it.withInitialModelProposalDismissed() } }
                    scrollToModels()
                },
                onDismiss = { showModelProposal = false },
            )
        }

        if (showOverlayCard) {
            AnonenDialog(
                title = "重ねて表示を許可する",
                onDismissRequest = { showOverlayCard = false },
                primary =
                    DialogButton("設定を開く", {
                        showOverlayCard = false
                        openOverlaySettings()
                        watchOverlayGrant()
                    }),
                secondary = DialogButton("後で", { showOverlayCard = false }, filled = false),
            ) {
                Text("1. 一覧から「あのねん」を探す", style = MaterialTheme.typography.bodyMedium)
                Text("2. スイッチをオンにする", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showMicExplain) {
            AnonenDialog(
                title = "マイクを使います",
                onDismissRequest = { showMicExplain = false },
                primary =
                    DialogButton("マイクを許可する", {
                        showMicExplain = false
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }),
                secondary = DialogButton("やめる", { showMicExplain = false }, filled = false),
            ) {
                Text("マイクを使わないと、文字起こしできません", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showAccessibilityDisclosure) {
            AccessibilityDisclosureDialog(
                onDismiss = { showAccessibilityDisclosure = false },
                onProceed = {
                    showAccessibilityDisclosure = false

                    scope.launch {
                        app.settingsRepository.update {
                            it.copy(
                                accessibilitySetupStartedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    context.startActivity(accessibilitySettingsIntent(context))
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        UserNoticesCard(app)
        Spacer(Modifier.onGloballyPositioned { modelSectionTop = it.positionInParent().y.toInt() })
        settingsOrNull?.let { settings ->
            ModelSelectionSection(
                app = app,
                settings = settings,
                scope = scope,
                update = { transform ->
                    scope.launch { app.settingsRepository.update(transform) }
                },
            )
        }
        Spacer(Modifier.onGloballyPositioned { trySectionTop = it.positionInParent().y.toInt() })
        TryItSection(
            accessibility = permissions.accessibility,
            pushToTalk = settingsOrNull?.pushToTalk == true,
            focusNonce = tryFocusNonce,
        )

        DevFeaturesProvider.instance.HomeSection()
    }
}

@Composable
private fun TryItSection(
    accessibility: Boolean,
    pushToTalk: Boolean,
    focusNonce: Int,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(focusNonce) {
        if (focusNonce == 0) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    SettingsGroup("試す") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                tryItStopHint(pushToTalk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!accessibility) {
                Text(
                    "話した後、ここを長押しして貼り付けます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                placeholder = { Text("ここをタップして話す") },
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (text.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { text = "" }) { Text("削除") }
                }
            }
        }
    }
}

private fun accessibilitySettingsIntent(context: Context): Intent {
    val component =
        ComponentName(context, AnonenAccessibilityService::class.java).flattenToString()
    return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        putExtra(":settings:fragment_args_key", component)
        putExtra(
            ":settings:show_fragment_args",
            Bundle().apply { putString(":settings:fragment_args_key", component) },
        )
    }
}

@Composable
private fun AccessibilityDisclosureDialog(
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
) {
    AnonenDialog(
        title = "ユーザー補助を ON にする",
        onDismissRequest = onDismiss,
        primary = DialogButton("同意して設定を開く", onProceed),
        secondary = DialogButton("後で", onDismiss, filled = false),
    ) {
        AccessibilityDisclosure.rows.forEach { (label, value) ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("ON にする手順", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("1. 下の「同意して設定を開く」を押す", style = MaterialTheme.typography.bodyMedium)
        Text("2. 「あのねん テキスト入力」をタップ", style = MaterialTheme.typography.bodyMedium)
        Text(
            "見つからなければ「インストール済みのアプリ」か「ダウンロードしたアプリ」の中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("3. スイッチを ON にして「許可」を押す", style = MaterialTheme.typography.bodyMedium)
        Text("4. 戻るボタンで、あのねんに戻る", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ButtonModeChoiceDialog(
    onDismiss: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onUseWithout: () -> Unit,
) {
    AnonenDialog(
        title = "ボタンの出し方",
        onDismissRequest = onDismiss,
        primary = DialogButton("入力中だけ出す", onEnableAccessibility),
        secondary = DialogButton("出したままにする", onUseWithout, filled = false),
    ) {
        Text("入力中だけ出す（おすすめ）", style = MaterialTheme.typography.titleSmall)
        Text(
            "文字も自動で入ります。ユーザー補助を ON にします",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("出したままにする", style = MaterialTheme.typography.titleSmall)
        Text(
            "文字は長押しで貼り付けます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    required: Boolean,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !granted, role = Role.Button, onClick = onRequest)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (granted) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (required) "必要" else "おすすめ",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (required && !granted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        if (!granted) {
            OutlinedButton(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) { Text("許可する", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private const val OVERLAY_WATCH_INTERVAL_MS = 500L
private const val OVERLAY_WATCH_TRIES = 240

internal fun bringAppToFront(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}
