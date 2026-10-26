package net.anonen.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.core.AccessControl
import net.anonen.app.core.ModelIds
import net.anonen.app.core.ThemeMode
import net.anonen.app.core.UPDATE_REQUIRED_MESSAGE
import net.anonen.app.core.UPDATE_REQUIRED_REASON
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.overlay.UPDATE_ACTION_LABEL
import net.anonen.app.overlay.UPDATE_DISMISS_LABEL
import net.anonen.app.overlay.openStorePage

class MainActivity : ComponentActivity() {
    private var chooseModelRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            chooseModelRequested = opensModelChoice(intent.getBooleanExtra(EXTRA_CHOOSE_MODEL, false), intent.flags)

            AnonenApp.from(this).updateNoticeClosedSince.value = null
        }
        setContent {
            val app = AnonenApp.from(this)
            val settings by app.settingsRepository.settings.collectAsState(initial = null)
            val gate by app.entitlement.gate.collectAsState()

            val themeMode =
                if (gate == AccessControl.AccessGate.ALLOWED) {
                    settings?.themeMode ?: ThemeMode.SYSTEM
                } else {
                    ThemeMode.SYSTEM
                }
            AnonenTheme(themeMode = themeMode) {
                AccessGatedApp(app, chooseModelRequested) { chooseModelRequested = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (opensModelChoice(intent.getBooleanExtra(EXTRA_CHOOSE_MODEL, false), intent.flags)) {
            chooseModelRequested = true
        }
    }

    companion object {
        private const val EXTRA_CHOOSE_MODEL = "net.anonen.app.extra.CHOOSE_MODEL"

        fun chooseModelIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(EXTRA_CHOOSE_MODEL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

internal fun opensModelChoice(
    chooseModel: Boolean,
    flags: Int,
): Boolean = chooseModel && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

@Composable
private fun AccessGatedApp(
    app: AnonenApp,
    chooseModel: Boolean,
    onChooseModelHandled: () -> Unit,
) {
    val gate by app.entitlement.gate.collectAsState()
    val scope = rememberCoroutineScope()

    var showHistory by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    app.entitlement.refresh()
                    if (app.cloudAuth.isSignedIn()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { app.cloudUsage.fetch() } }
                        }

                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val model = app.settingsRepository.current().selectedModel
                                if (model.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) {
                                    runCatching { app.enclaveKeys.prefetch() }
                                }
                            }
                        }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = showHistory) { showHistory = false }
    when {
        gate == AccessControl.AccessGate.ALLOWED -> {
            AnonenAppUi(chooseModel, onChooseModelHandled)

            UpdateRequiredNotice(app)
        }

        showHistory -> HistoryWallScreen(onBack = { showHistory = false })

        gate == AccessControl.AccessGate.CHECKING -> CheckingScreen()
        gate == AccessControl.AccessGate.NEEDS_LOGIN ->

            LoginScreen(
                app,
                onSignedIn = { scope.launch { app.completeSignInAndRefresh() } },
                onViewHistory = { showHistory = true },
            )
        else ->
            SubscriptionRequiredScreen(
                app,
                onViewHistory = { showHistory = true },
                checkFailed = gate == AccessControl.AccessGate.CHECK_FAILED,
            )
    }
}

@Composable
private fun UpdateRequiredNotice(app: AnonenApp) {
    val context = LocalContext.current
    val outdated by app.enclaveKeys.outdated.collectAsState()
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val asked by app.retranscriber.updateRequired.collectAsState()
    val closedSince by app.updateNoticeClosedSince.collectAsState()
    val since = outdated?.since
    val cloud = settings?.selectedModel?.startsWith(ModelIds.ANONEN_CLOUD_PREFIX) == true
    if (!showsUpdateNotice(asked, since, closedSince, cloud)) return
    val close = {
        app.updateNoticeClosedSince.value = since
        app.retranscriber.consumeUpdateRequired()
    }
    AnonenConfirmDialog(
        title = UPDATE_REQUIRED_MESSAGE,
        text = UPDATE_REQUIRED_REASON,
        onDismissRequest = close,
        confirmLabel = UPDATE_ACTION_LABEL,
        onConfirm = {
            close()
            openStorePage(context)
        },
        dismissLabel = UPDATE_DISMISS_LABEL,
        onDismiss = close,
    )
}

internal fun showsUpdateNotice(
    asked: Boolean,
    since: Long?,
    closedSince: Long?,
    cloud: Boolean,
): Boolean = asked || (since != null && since != closedSince && cloud)

@Composable
private fun AnonenAppUi(
    chooseModel: Boolean,
    onChooseModelHandled: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val devTabs = DevFeaturesProvider.instance.extraTabs

    LaunchedEffect(chooseModel) { if (chooseModel) tab = 0 }

    BackHandler(enabled = tab != 0) { tab = 0 }
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "ホーム") },
                    label = { Text("ホーム") },
                    colors = navItemColors(),
                )

                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Refresh, contentDescription = "記録") },
                    label = { Text("記録") },
                    colors = navItemColors(),
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                    label = { Text("設定") },
                    colors = navItemColors(),
                )
                devTabs.forEachIndexed { i, devTab ->
                    NavigationBarItem(
                        selected = tab == 3 + i,
                        onClick = { tab = 3 + i },
                        icon = { Icon(devTab.icon, contentDescription = devTab.title) },
                        label = { Text(devTab.title) },
                        colors = navItemColors(),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(chooseModel, onChooseModelHandled)
                1 -> HistoryScreen()
                2 -> SettingsScreen()
                else -> devTabs.getOrNull(tab - 3)?.content?.invoke()
            }
        }
    }
}

@Composable
private fun navItemColors() =
    NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
