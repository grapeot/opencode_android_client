package com.yage.opencode_client

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.flow.first
import com.yage.opencode_client.ambient.ui.AmbientNavigationTags
import com.yage.opencode_client.ambient.ui.AmbientRoute
import com.yage.opencode_client.ui.AppWidthClass
import com.yage.opencode_client.ui.EffectiveLayout
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.resolveEffectiveLayout
import com.yage.opencode_client.ui.DeepLinkError
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequest
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequestSaver
import com.yage.opencode_client.ui.chat.ChatScreen
import com.yage.opencode_client.ui.chat.belongsTo
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.files.FilesViewModel
import com.yage.opencode_client.ui.session.SessionList
import com.yage.opencode_client.ui.settings.SettingsScreen
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import com.yage.opencode_client.ui.theme.compactTypography
import com.yage.opencode_client.util.AppLocaleController
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

sealed class Screen(
    val route: String,
    val titleRes: Int,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Chat : Screen(
        "chat",
        R.string.nav_chat,
        Icons.AutoMirrored.Filled.Chat,
        Icons.Outlined.ChatBubbleOutline
    )

    object Files : Screen(
        "files",
        R.string.nav_files,
        Icons.Default.Folder,
        Icons.Outlined.Folder
    )

    object Settings : Screen(
        "settings",
        R.string.nav_settings,
        Icons.Default.Settings,
        Icons.Outlined.Settings
    )

    object Ambient : Screen(
        "ambient",
        R.string.ambient_nav,
        Icons.Filled.GraphicEq,
        Icons.Outlined.GraphicEq
    )
}

val screens = listOf(Screen.Chat, Screen.Files, Screen.Settings)

// Debug-only Intent extra keys for injecting connection credentials at launch,
// so automated UI tests can connect to a server without driving the Settings UI.
// Read only when BuildConfig.DEBUG is true (see onCreate).
private const val EXTRA_TEST_SERVER_URL = "test_server_url"
private const val EXTRA_TEST_USERNAME = "test_username"
private const val EXTRA_TEST_PASSWORD = "test_password"

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            mainViewModel = hiltViewModel()
            // Process any NFC prompt that arrived before ViewModel was ready
            pendingNfcPrompt?.let { (prompt, autoSend) ->
                pendingNfcPrompt = null
                mainViewModel.handleNfcPrompt(prompt, autoSend)
            }
            pendingDeepLinkUrl?.let { rawUrl ->
                pendingDeepLinkUrl = null
                mainViewModel.receiveDeepLink(rawUrl)
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, mainViewModel) {
                var hostToken = 0L
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        hostToken = mainViewModel.onHostActivityStart()
                    }
                    if (event == Lifecycle.Event.ON_STOP) {
                        mainViewModel.onHostActivityStop(
                            token = hostToken,
                            changingConfigurations = this@MainActivity.isChangingConfigurations,
                        )
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(lifecycleOwner) {
                // Debug-only credential injection: if the launch Intent carries
                // test credentials (passed via `am start --es test_server_url ...`),
                // configure the server before testing the connection so automated
                // tests skip the Settings UI entirely. Gated hard on BuildConfig.DEBUG
                // so this path is dead code in release builds.
                if (BuildConfig.DEBUG) {
                    val testUrl = intent?.getStringExtra(EXTRA_TEST_SERVER_URL)
                    if (!testUrl.isNullOrEmpty()) {
                        mainViewModel.configureServer(
                            url = testUrl,
                            username = intent?.getStringExtra(EXTRA_TEST_USERNAME),
                            password = intent?.getStringExtra(EXTRA_TEST_PASSWORD)
                        )
                    }
                }
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    mainViewModel.testConnection()
                }
            }
            val state by mainViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.languageMode) {
                AppLocaleController.apply(state.languageMode)
            }
            val darkTheme = when (state.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val windowSizeClass = calculateWindowSizeClass(this)
            val widthClass = when (windowSizeClass.widthSizeClass) {
                WindowWidthSizeClass.Compact -> AppWidthClass.COMPACT
                WindowWidthSizeClass.Medium -> AppWidthClass.MEDIUM
                WindowWidthSizeClass.Expanded -> AppWidthClass.EXPANDED
                else -> AppWidthClass.COMPACT
            }
            val effectiveLayout = resolveEffectiveLayout(state.layoutPreference, widthClass)
            var dockedPreviewRequest by rememberSaveable(stateSaver = ChatFilePreviewRequestSaver) {
                mutableStateOf<ChatFilePreviewRequest?>(null)
            }
            LaunchedEffect(
                effectiveLayout,
                state.appliedLayout,
                state.currentHostProfileId,
                state.currentSessionId,
                state.currentSession?.directory,
                state.columnPreview,
                dockedPreviewRequest,
            ) {
                val inline = mainViewModel.reconcilePreviewLayout(effectiveLayout, dockedPreviewRequest)
                if (dockedPreviewRequest != inline) dockedPreviewRequest = inline
            }
            var ambientDestinationVisible by rememberSaveable { mutableStateOf(false) }
            var appliedDeepLinkVersion by rememberSaveable { mutableLongStateOf(state.deepLinkNavigationVersion) }
            LaunchedEffect(state.ambientEnabled) {
                if (!state.ambientEnabled) ambientDestinationVisible = false
            }
            LaunchedEffect(state.deepLinkNavigationVersion) {
                if (state.deepLinkNavigationVersion == appliedDeepLinkVersion) return@LaunchedEffect
                appliedDeepLinkVersion = state.deepLinkNavigationVersion
                if (state.deepLinkNavigationVersion > 0) ambientDestinationVisible = false
            }

            OpenCodeTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (effectiveLayout == EffectiveLayout.REGULAR) {
                        TabletLayout(
                            viewModel = mainViewModel,
                            ambientDestinationVisible = ambientDestinationVisible,
                            onAmbientDestinationVisible = { ambientDestinationVisible = it },
                        )
                    } else {
                        PhoneLayout(
                            viewModel = mainViewModel,
                            dockedPreviewRequest = dockedPreviewRequest,
                            onDockedPreviewRequestChange = { dockedPreviewRequest = it },
                            ambientDestinationVisible = ambientDestinationVisible,
                            onAmbientDestinationVisible = { ambientDestinationVisible = it },
                        )
                    }
                    DeepLinkFeedback(
                        isResolving = state.isResolvingDeepLink,
                        error = state.deepLinkError,
                        onDismissError = mainViewModel::clearDeepLinkError
                    )
                }
            }
        }
        // Cold-start intents arrive through getIntent(); warm intents use onNewIntent.
        handleIncomingIntent(intent)
    }

    private var lastNfcTriggerTimeMs: Long = 0
    private var pendingNfcPrompt: Pair<String, Boolean>? = null
    private var pendingDeepLinkUrl: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        handleNfcIntent(intent)
        if (intent?.action != Intent.ACTION_VIEW) return
        val rawUrl = intent.data?.toString() ?: return
        if (::mainViewModel.isInitialized) {
            mainViewModel.receiveDeepLink(rawUrl)
        } else {
            pendingDeepLinkUrl = rawUrl
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        android.util.Log.d("MainActivity", "handleNfcIntent: action=${intent?.action}")
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val data: Uri = intent.data ?: return
        if (data.scheme != "opencode" || data.host != "prompt") return

        val now = System.currentTimeMillis()
        if (now - lastNfcTriggerTimeMs < 30_000L) {
            android.util.Log.d("MainActivity", "NFC debounce: ignored (${now - lastNfcTriggerTimeMs}ms since last)")
            return
        }
        lastNfcTriggerTimeMs = now

        val prompt = data.getQueryParameter("p") ?: return
        val autoSend = data.getQueryParameter("a") == "1"
        android.util.Log.d("MainActivity", "NFC prompt: ${prompt.take(50)}..., autoSend=$autoSend, vmInit=${::mainViewModel.isInitialized}")
        if (::mainViewModel.isInitialized) {
            mainViewModel.handleNfcPrompt(prompt, autoSend)
        } else {
            // ViewModel not ready yet (onNewIntent arrived before setContent).
            // Stash it; setContent's LaunchedEffect will pick it up.
            pendingNfcPrompt = prompt to autoSend
        }
    }
}

@Composable
private fun PhoneLayout(
    viewModel: MainViewModel,
    dockedPreviewRequest: ChatFilePreviewRequest?,
    onDockedPreviewRequestChange: (ChatFilePreviewRequest?) -> Unit,
    ambientDestinationVisible: Boolean,
    onAmbientDestinationVisible: (Boolean) -> Unit,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destinations = if (state.ambientEnabled) {
        // Same phone and focus order as iPhone: Chat, Files, Ambient, Settings.
        listOf(Screen.Chat, Screen.Files, Screen.Ambient, Screen.Settings)
    } else {
        screens
    }
    val restoreAmbientOnEntry = remember {
        ambientDestinationVisible && state.ambientEnabled && state.deepLinkNavigationVersion == 0L
    }
    val ambientVisibleNow = rememberUpdatedState(ambientDestinationVisible)

    suspend fun awaitBackStackReady() {
        navController.currentBackStackEntryFlow.first()
    }

    fun navigateToTopLevel(route: String) {
        if (navController.currentDestination?.route == route) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            when (destination.route) {
                Screen.Ambient.route -> onAmbientDestinationVisible(true)
                Screen.Chat.route -> onAmbientDestinationVisible(false)
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    BackHandler(enabled = currentRoute == Screen.Ambient.route) {
        onAmbientDestinationVisible(false)
        navigateToTopLevel(Screen.Chat.route)
    }

    LaunchedEffect(Unit) {
        if (!restoreAmbientOnEntry) return@LaunchedEffect
        awaitBackStackReady()
        val latest = viewModel.state.value
        if (!latest.ambientEnabled || latest.deepLinkNavigationVersion > 0L || !ambientVisibleNow.value) {
            return@LaunchedEffect
        }
        val route = navController.currentDestination?.route
        if (route == Screen.Files.route || route == Screen.Settings.route || route == Screen.Ambient.route) {
            return@LaunchedEffect
        }
        navigateToTopLevel(Screen.Ambient.route)
    }

    LaunchedEffect(state.ambientEnabled) {
        if (!state.ambientEnabled && navController.currentDestination?.route == Screen.Ambient.route) {
            navigateToTopLevel(Screen.Chat.route)
        }
    }

    LaunchedEffect(state.deepLinkNavigationVersion) {
        if (state.deepLinkNavigationVersion <= 0L) return@LaunchedEffect
        awaitBackStackReady()
        if (viewModel.state.value.deepLinkNavigationVersion > 0L) {
            navigateToTopLevel(Screen.Chat.route)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            NavigationBar {
                destinations.forEach { screen ->
                    val selected = currentRoute == screen.route
                    val title = stringResource(screen.titleRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (screen == Screen.Chat) onAmbientDestinationVisible(false)
                            if (screen == Screen.Ambient) onAmbientDestinationVisible(true)
                            navigateToTopLevel(screen.route)
                        },
                        icon = {
                            Icon(
                                if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        modifier = if (screen == Screen.Ambient) {
                            Modifier.testTag(AmbientNavigationTags.PHONE_NAV)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToFiles = { path ->
                        viewModel.showFileInFiles(path, originRoute = Screen.Chat.route)
                        navigateToTopLevel(Screen.Files.route)
                    },
                    dockedPreviewRequest = dockedPreviewRequest,
                    onDockedPreviewRequestChange = onDockedPreviewRequestChange,
                    onNavigateToSettings = {
                        navigateToTopLevel(Screen.Settings.route)
                    },
                    onManageModels = {
                        navigateToTopLevel(Screen.Settings.route)
                    },
                    showSettingsButton = false
                )
            }
            composable(Screen.Files.route) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val filesViewModel: FilesViewModel = hiltViewModel(
                    key = "files-${state.currentHostProfileId ?: "none"}"
                )
                LaunchedEffect(state.currentHostProfileId) {
                    filesViewModel.resetForHost()
                }
                FilesScreen(
                    viewModel = filesViewModel,
                    pathToShow = state.filePathToShowInFiles,
                    sessionDirectory = state.columnPreview?.workspace,
                    onCloseFile = {
                        val origin = state.filePreviewOriginRoute
                        viewModel.clearFileToShow()
                        if (origin == Screen.Chat.route) {
                            onAmbientDestinationVisible(false)
                            navigateToTopLevel(Screen.Chat.route)
                        }
                    },
                    onFileClick = { },
                    onPreviewPathChange = viewModel::retargetColumnPreview,
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(Screen.Ambient.route) {
                AmbientRoute(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun BoxScope.DeepLinkFeedback(
    isResolving: Boolean,
    error: DeepLinkError?,
    onDismissError: () -> Unit
) {
    if (isResolving) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("deep-link-opening"),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.deep_link_opening))
            }
        }
    }

    if (error != null) {
        Snackbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .testTag("deep-link-error"),
            action = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.common_dismiss))
                }
            }
        ) {
            Text(
                stringResource(
                    when (error) {
                        DeepLinkError.INVALID -> R.string.deep_link_invalid
                        DeepLinkError.SESSION_UNAVAILABLE -> R.string.deep_link_session_unavailable
                        DeepLinkError.OPEN_FAILED -> R.string.deep_link_open_failed
                    }
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletLayout(
    viewModel: MainViewModel,
    ambientDestinationVisible: Boolean,
    onAmbientDestinationVisible: (Boolean) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sessionsPaneCollapsed by rememberSaveable { mutableStateOf(false) }
    // Opening Settings (e.g. from the chat "Manage models" jump) must also expand
    // the left pane, otherwise the Settings screen isn't composed when the Sessions
    // pane is collapsed and the pending model-shortlist focus is never consumed.
    val onOpenSettings: () -> Unit = {
        sessionsPaneCollapsed = false
        selectedTab = 1
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filesWeight = if (sessionsPaneCollapsed) 0.5f else 0.375f
    val chatWeight = if (sessionsPaneCollapsed) 0.5f else 0.375f

        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
        // Left panel: Session list or Settings — 25% when expanded.
        if (!sessionsPaneCollapsed) {
            Column(
                modifier = Modifier
                    .weight(0.25f)
                    .fillMaxHeight()
            ) {
                if (selectedTab == 1) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { selectedTab = 0 }
                    )
                } else {
                    SessionList(
                        presentations = state.sessionPresentations,
                        sessions = state.sessions,
                        currentSessionId = state.currentSessionId,
                        sessionStatuses = state.sessionStatuses,
                        attentionSessionIds = state.attentionSessionIds,
                        descendantBusyCounts = state.sessionDescendantBusyCounts,
                        unreadCounts = state.sessionUnreadCounts,
                        failureCounts = state.sessionFailureCounts,
                        hasMoreSessions = state.hasMoreSessions,
                        isLoadingMoreSessions = state.isLoadingMoreSessions,
                        isRefreshingSessions = state.isRefreshingSessions,
                        expandedSessionIds = state.expandedSessionIds,
                        onSelectSession = { viewModel.selectSession(it) },
                        onCreateSession = { viewModel.createSession() },
                        onDeleteSession = { viewModel.deleteSession(it) },
                        onArchiveSession = { viewModel.archiveSession(it) },
                        onRestoreSession = { viewModel.restoreSession(it) },
                        onLoadMoreSessions = { viewModel.loadMoreSessions() },
                        onRefreshSessions = { viewModel.loadSessions() },
                        onToggleSessionExpanded = { viewModel.toggleSessionExpanded(it) },
                        onOpenSettings = { selectedTab = 1 },
                        onCollapseSessions = { sessionsPaneCollapsed = true }
                    )
                }
            }

            VerticalDivider()
        }

        // Middle panel: FilesScreen (file preview) — 37.5%, or 50% when Sessions is collapsed.
        Column(
            modifier = Modifier
                .weight(filesWeight)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                val filesViewModel: FilesViewModel = hiltViewModel(
                    key = "files-${state.currentHostProfileId ?: "none"}"
                )
                LaunchedEffect(state.currentHostProfileId) {
                    filesViewModel.resetForHost()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    FilesScreen(
                        viewModel = filesViewModel,
                        pathToShow = state.filePathToShowInFiles,
                        sessionDirectory = state.columnPreview?.workspace,
                        onCloseFile = { viewModel.clearFileToShow() },
                        onFileClick = { path -> viewModel.showFilesBrowserFile(path) },
                        onPreviewPathChange = viewModel::retargetColumnPreview,
                    )
                    if (sessionsPaneCollapsed) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            tonalElevation = 3.dp
                        ) {
                            IconButton(onClick = { sessionsPaneCollapsed = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = stringResource(R.string.sessions_show)
                                )
            }
        }
    }
                }
            }
        }

        VerticalDivider()

        // Right panel: Chat — 37.5%, or 50% when Sessions is collapsed.
        Column(
            modifier = Modifier
                .weight(chatWeight)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.ambientEnabled) {
                        AmbientContentSwitch(
                            ambientSelected = ambientDestinationVisible,
                            onSelectChat = { onAmbientDestinationVisible(false) },
                            onSelectAmbient = { onAmbientDestinationVisible(true) },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (ambientDestinationVisible && state.ambientEnabled) {
                            AmbientRoute(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ChatScreen(
                                viewModel = viewModel,
                                onNavigateToFiles = { path ->
                                    viewModel.showFileInFiles(path)
                                },
                                useInlineFilePreview = true,
                                onNavigateToSettings = onOpenSettings,
                                onManageModels = onOpenSettings,
                                showSettingsButton = false,
                                showNewSessionInTopBar = false,
                                showSessionListInTopBar = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmbientContentSwitch(
    ambientSelected: Boolean,
    onSelectChat: () -> Unit,
    onSelectAmbient: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(AmbientNavigationTags.CONTENT_SWITCH),
    ) {
        SegmentedButton(
            selected = !ambientSelected,
            onClick = onSelectChat,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag(AmbientNavigationTags.CONTENT_CHAT),
            icon = {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
            },
        ) {
            Text(stringResource(R.string.nav_chat))
        }
        SegmentedButton(
            selected = ambientSelected,
            onClick = onSelectAmbient,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag(AmbientNavigationTags.CONTENT_AMBIENT),
            icon = {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
            },
        ) {
            Text(stringResource(R.string.ambient_nav))
        }
    }
}
