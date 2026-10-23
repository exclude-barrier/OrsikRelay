package io.github.excludebarrier.orsikrelay.navigation

import io.github.excludebarrier.orsikrelay.ui.home.QuickTask
import io.github.excludebarrier.orsikrelay.ui.files.ProjectsRoute
import io.github.excludebarrier.orsikrelay.ui.files.FilesRoute
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalRoute
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.ui.home.createPreparedChat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.excludebarrier.orsikrelay.domain.home.ServerConnectionCheck
import io.github.excludebarrier.orsikrelay.ui.home.HomeRoute
import io.github.excludebarrier.orsikrelay.ui.activity.ActivityRoute
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.fragment.app.FragmentActivity
import io.github.excludebarrier.orsikrelay.BuildConfig
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.RunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesLanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesMaterialYouSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.SharedPreferencesThemeSettingStore
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionManager
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.platform.locale.AppLocale
import io.github.excludebarrier.orsikrelay.security.applock.AndroidAppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.security.applock.AppLockHolder
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.onboarding.WelcomeScreen
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.AboutScreen
import io.github.excludebarrier.orsikrelay.ui.settings.ChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.LanguageController
import io.github.excludebarrier.orsikrelay.ui.settings.MaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.ScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.rememberChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberMaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64

@Composable
fun HermesNavHost(
    startDestination: AppScreen,
    navController: NavHostController = rememberNavController(),
    connectionCoordinator: ProfileConnectionCoordinator? = null,
    sessionPageSource: SessionPageSource? = null,
    messageHistorySource: MessageHistorySource? = null,
    sessionCreator: SessionCreator? = null,
    sessionManager: SessionManager? = null,
    runController: RunSessionController? = null,
    runStopRequester: RunStopRequester? = null,
    runSteerRequester: RunSteerRequester? = null,
    runApprovalRequester: RunApprovalRequester? = null,
    runStatusRequester: RunStatusRequester? = null,
    serverConnectionCheck: ServerConnectionCheck? = null,
    chatDraftRepository: ChatDraftRepository? = null,
    profileAdmin: ProfileAdmin? = null,
    themeController: ThemeModeController? = null,
    languageController: LanguageController? = null,
    chatTextSizeController: ChatTextSizeController? = null,
    screenProtectionController: ScreenProtectionController? = null,
    materialYouController: MaterialYouController? = null,
    appLockController: AppLockController? = null,
    appAuthenticator: AppAuthenticator? = null,
    terminalSource: TerminalSource? = null,
    filesSource: FilesSource? = null,
    startupProfileResolver: (suspend () -> StartupProfile)? = null,
    selectionStore: ActiveServerSelectionStore? = null,
    capabilityStore: RunApprovalCapabilityStore? = null,
    // Product Gate: pairing deep-link bootstrap (server URL + code) pre-filled
    // into the onboarding route. Null when no deep link was received.
    pairingBootstrap: PairingBootstrap? = null,
    // A validated notification tap (profileId, sessionId, delivery) to open
    // directly. Consumed exactly once per delivery; null when no tap is
    // pending.
    notificationBootstrap: NotificationTap? = null
) {
    val applicationContext = LocalContext.current.applicationContext
    // The settings screen owns both selections. Production passes the theme
    // controller from MainActivity; tests may inject either controller.
    val themeStore = remember(applicationContext) {
        SharedPreferencesThemeSettingStore(applicationContext)
    }
    val settingsThemeController = themeController
        ?: rememberThemeModeController(themeStore)
    val languageStore = remember(applicationContext) {
        SharedPreferencesLanguageSettingStore(applicationContext)
    }
    val currentActivity = LocalActivity.current
    val resolvedLanguageController = languageController ?: rememberLanguageController(
        store = languageStore,
        applySelection = { tag -> currentActivity?.let { AppLocale.applySelection(it, tag) } }
    )
    val chatTextSizeStore = remember(applicationContext) {
        SharedPreferencesChatTextSizeSettingStore(applicationContext)
    }
    val resolvedChatTextSizeController = chatTextSizeController
        ?: rememberChatTextSizeController(chatTextSizeStore)
    val screenProtectionStore = remember(applicationContext) {
        SharedPreferencesScreenProtectionSettingStore(applicationContext)
    }
    val resolvedScreenProtectionController = screenProtectionController
        ?: rememberScreenProtectionController(screenProtectionStore)
    val materialYouStore = remember(applicationContext) {
        SharedPreferencesMaterialYouSettingStore(applicationContext)
    }
    val resolvedMaterialYouController = materialYouController
        ?: rememberMaterialYouController(materialYouStore)
    // The app-level lock state is process-scoped; production passes the same
    // controller from MainActivity so the root gate and the settings switch
    // can never diverge.
    val resolvedAppLockController = appLockController
        ?: AppLockHolder.get(applicationContext)
    val resolvedAppAuthenticator = appAuthenticator
        ?: (currentActivity as? FragmentActivity)?.let(::AndroidAppAuthenticator)
    // The graph (and with it the app-level live-run
    // state) is process-scoped, so an Activity/configuration recreation
    // re-attaches to the still-running turn instead of building a second,
    // empty graph. Test-injected stores still get a fresh graph.
    val productionDependencies = remember(applicationContext) {
        lazy { AppDependenciesHolder.get(applicationContext, selectionStore, capabilityStore) }
    }

    val navHostCoroutineScope = rememberCoroutineScope()
    var resolvedStart by remember(startDestination) { mutableStateOf<AppScreen?>(null) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var pendingCreatedSessionId by remember { mutableStateOf<String?>(null) }
    // Set by the Welcome screen: open the onboarding form with its QR
    // scanner already showing. A plain UI hint; pairing itself is unchanged.
    var onboardingOpensScanner by remember { mutableStateOf(false) }
    // The session list already knows the title of the tapped session. Keep it
    // here so the chat header can show it without a second list/history read;
    // a notification tap or a direct chat route starts with the generic
    // fallback instead. The ID pins the title to its session, so a restored
    // chat destination can never display another session's title.
    var activeSessionTitleSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeSessionTitle by rememberSaveable { mutableStateOf<String?>(null) }

    // Safe return after a profile deletion: the whole back stack (Profiles
    // and every chat entry) is cleared and a single new Home entry
    // re-derives everything from [activeProfileId]. Deleting the active
    // profile resets the ID so Home falls into its unreachable state - no
    // chat can keep living under the deleted profile.
    suspend fun profileDeleted(profileId: String) {
        // The caller invokes this from the deletion coroutine, which may
        // resume off the main thread (e.g. under the Compose test
        // dispatcher). NavController mutations and the state write below
        // must run on the main thread.
        withContext(Dispatchers.Main) {
            if (activeProfileId == profileId) {
                activeProfileId = ""
            }
            navController.navigate(routeOf(AppScreen.Home)) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Safe switch of the active server. Same pattern as [profileDeleted],
    // but [activeProfileId] becomes the chosen server: clearing the back
    // stack removes every chat of the previous server, and the single new
    // Home entry reloads under the new ID. The selection path itself reads
    // no credential.
    suspend fun serverSelected(profileId: String) {
        // The caller invokes this from the ProfilesScreen select button,
        // which runs on the main thread, but the NavController mutation and
        // the state write are still guarded the same way as [profileDeleted]
        // so the transition is always main-thread safe.
        withContext(Dispatchers.Main) {
            activeProfileId = profileId
            navController.navigate(routeOf(AppScreen.Home)) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(startDestination) {
        if (startDestination is AppScreen.Sessions) {
            resolvedStart = AppScreen.Sessions
            return@LaunchedEffect
        }
        val startupProfile = try {
            withContext(Dispatchers.IO) {
                startupProfileResolver?.invoke()
                    ?: productionDependencies.value.startupResolver()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            StartupProfile.Unavailable
        }
        when (startupProfile) {
            StartupProfile.None ->
                resolvedStart = startScreenFor(startupProfile, pairingDeepLink = pairingBootstrap != null)
            is StartupProfile.Ready -> {
                activeProfileId = startupProfile.profileId
                resolvedStart = AppScreen.Home
            }
            StartupProfile.Unavailable -> {
                activeProfileId = ""
                resolvedStart = AppScreen.Home
            }
        }
    }

    LaunchedEffect(pendingCreatedSessionId) {
        val sessionId = pendingCreatedSessionId ?: return@LaunchedEffect
        navController.navigate(routeOf(AppScreen.Chat(sessionId))) {
            launchSingleTop = true
        }
        pendingCreatedSessionId = null
    }

    // A validated notification tap (profileId, sessionId) is consumed
    // exactly once. `pendingNotificationTap` mirrors the latest tap from the
    // activity (cold start via onCreate, warm start via onNewIntent) so the
    // consuming effect below can clear it after navigating.
    var pendingNotificationTap by remember { mutableStateOf(notificationBootstrap) }
    LaunchedEffect(notificationBootstrap) {
        if (notificationBootstrap != null) pendingNotificationTap = notificationBootstrap
    }
    // Open the chat targeted by a validated notification tap. The effect
    // waits until the navigation host is composed (resolvedStart non-null) and
    // then switches the active profile to the tap's server (so the chat
    // authenticates under the right server) and navigates to exactly that
    // chat. A revoked or missing credential fails closed in the chat's
    // existing history-load path; no navigation bypasses authentication.
    LaunchedEffect(resolvedStart, pendingNotificationTap) {
        if (resolvedStart == null) return@LaunchedEffect
        val bootstrap = pendingNotificationTap ?: return@LaunchedEffect
        withContext(Dispatchers.Main) {
            activeProfileId = bootstrap.profileId
            activeSessionTitleSessionId = null
            activeSessionTitle = null
            navController.navigate(routeOf(AppScreen.Chat(bootstrap.sessionId))) {
                launchSingleTop = true
            }
        }
        pendingNotificationTap = null
    }

    val initialScreen = resolvedStart
    if (initialScreen == null) {
        Text(text = stringResource(R.string.sessions_loading))
        return
    }

    fun openChat(sessionId: String, title: String?) {
        activeSessionTitleSessionId = sessionId
        activeSessionTitle = title
        navController.navigate(routeOf(AppScreen.Chat(sessionId))) {
            launchSingleTop = true
        }
    }

    // A tab keeps Home underneath it, so back from any tab returns to Home
    // and back on Home leaves the app.
    fun openTab(tab: AppScreen) {
        navController.navigate(routeOf(tab)) {
            popUpTo(routeOf(AppScreen.Home))
            launchSingleTop = true
        }
    }

    fun openTool(task: QuickTask) {
        val screen = when (task) {
            QuickTask.Terminal -> AppScreen.Terminal
            QuickTask.Files -> AppScreen.Files()
            QuickTask.Projects -> AppScreen.Projects
            QuickTask.Docker -> return
        }
        navController.navigate(routeOf(screen)) { launchSingleTop = true }
    }

    // Server management, shown as the Server tab and as the Profiles route
    // (opened from settings or the Home server switcher, with a back arrow).
    @Composable
    fun ServerManagement(onBack: (() -> Unit)?) {
        val admin = profileAdmin ?: productionDependencies.value.profileAdmin
        val check = serverConnectionCheck ?: productionDependencies.value.serverConnectionCheck
        // Reachability of the active server: the same read-only probe as
        // Home, never retried automatically.
        val activeReachable by produceState<Boolean?>(null, activeProfileId) {
            val profileId = activeProfileId?.takeIf { it.isNotBlank() } ?: return@produceState
            value = try {
                withContext(Dispatchers.IO) { check.check(profileId) } is HermesConnectionResult.Success
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
        ProfilesScreen(
            admin = admin,
            activeProfileId = activeProfileId,
            activeReachable = activeReachable,
            onBack = onBack,
            onProfileDeleted = { profileId ->
                navHostCoroutineScope.launch { profileDeleted(profileId) }
            },
            onServerSelected = { profileId ->
                navHostCoroutineScope.launch { serverSelected(profileId) }
            },
            onAddServer = {
                navController.navigate(routeOf(AppScreen.Onboarding))
            },
            // A confirmed, server-acknowledged disconnect
            // removes the local profile connection; the navigation is
            // exactly the verified profile-deletion path, so no chat or
            // session can keep living under the disconnected profile.
            onDeviceDisconnected = { profileId ->
                navHostCoroutineScope.launch { profileDeleted(profileId) }
            },
            disconnect = { profileId ->
                productionDependencies.value.deviceDisconnectCoordinator
                    .disconnect(profileId)
            },
            onOpenMonitoring = activeProfileId?.takeIf { it.isNotBlank() }?.let { { openTab(AppScreen.Home) } },
            onOpenTool = activeProfileId?.takeIf { it.isNotBlank() }?.let { { task: QuickTask -> openTool(task) } },
            startTask = activeProfileId?.takeIf { it.isNotBlank() }?.let { profileId ->
                { title: String, prompt: String ->
                    val created = withContext(Dispatchers.IO) {
                        createPreparedChat(
                            sessionCreator ?: productionDependencies.value.sessionCreator,
                            chatDraftRepository ?: productionDependencies.value.chatDraftRepository,
                            profileId, title, prompt
                        )
                    }
                    (created as? SessionCreateResult.Success)?.let { openChat(it.sessionId, it.title ?: title) } != null
                }
            }
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = BottomTabs.firstOrNull { routeOf(it) == currentRoute }

    Scaffold(
        // The screens keep handling the status bar and IME themselves; the
        // scaffold only reserves the bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentTab != null) {
                val resolvedRunController = runController
                    ?: productionDependencies.value.runController
                val activeRun by resolvedRunController.activeRun.collectAsState()
                val approvalPending = activeRun?.let {
                    it.profileId == activeProfileId &&
                        it.status == RunSessionController.ActiveRun.Status.WAITING_FOR_APPROVAL
                } == true
                AppBottomBar(
                    current = currentTab,
                    approvalPending = approvalPending,
                    onSelect = ::openTab
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = routeOf(initialScreen),
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(routeOf(AppScreen.Home)) {
                val dependencies = productionDependencies
                HomeRoute(
                    profileId = activeProfileId.orEmpty(),
                    profileAdmin = profileAdmin ?: dependencies.value.profileAdmin,
                    connectionCheck = serverConnectionCheck ?: dependencies.value.serverConnectionCheck,
                    sessionPageSource = sessionPageSource ?: dependencies.value.sessionPageSource,
                    sessionCreator = sessionCreator ?: dependencies.value.sessionCreator,
                    runController = runController ?: dependencies.value.runController,
                    runStatusRequester = runStatusRequester ?: dependencies.value.runStatusRequester,
                    attachmentSource = dependencies.value.chatAttachmentSource,
                    hostMetricsSource = dependencies.value.hostMetricsSource,
                    chatDraftRepository = chatDraftRepository ?: dependencies.value.chatDraftRepository,
                    onOpenChat = ::openChat,
                    onOpenAllSessions = { openTab(AppScreen.Sessions) },
                    onOpenServer = { openTab(AppScreen.Server) },
                    onSwitchServer = { navController.navigate(routeOf(AppScreen.Profiles)) },
                    onOpenSettings = {
                        navController.navigate(routeOf(AppScreen.Settings)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenTool = ::openTool,
                    onNoServer = {
                        navController.navigate(routeOf(AppScreen.Welcome)) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            // The Server tab is the server management itself, without a
            // way back (back from a tab returns to Home).
            composable(routeOf(AppScreen.Server)) {
                ServerManagement(onBack = null)
            }
            composable(routeOf(AppScreen.Activity)) {
                ActivityRoute(
                    profileId = activeProfileId.orEmpty(),
                    runController = runController ?: productionDependencies.value.runController,
                    runStatusRequester = runStatusRequester ?: productionDependencies.value.runStatusRequester,
                    onOpenChat = ::openChat
                )
            }
            composable(routeOf(AppScreen.Welcome)) {
                WelcomeScreen(
                    onScan = {
                        onboardingOpensScanner = true
                        navController.navigate(routeOf(AppScreen.Onboarding)) { launchSingleTop = true }
                    },
                    onEnterCode = {
                        onboardingOpensScanner = false
                        navController.navigate(routeOf(AppScreen.Onboarding)) { launchSingleTop = true }
                    }
                )
            }
            composable(routeOf(AppScreen.Onboarding)) {
                OnboardingRoute(
                    pairingBootstrap = pairingBootstrap,
                    startWithScanner = onboardingOpensScanner,
                    // Only an onboarding opened from Welcome or the server
                    // management screen has a destination to return to. The
                    // pairing deep link keeps its header unchanged.
                    onBack = navController.previousBackStackEntry?.let {
                        { navController.popBackStack() }
                    },
                    connect = { draft ->
                        val coordinator = connectionCoordinator
                            ?: productionDependencies.value.coordinator
                        coordinator.connect(draft)
                    },
                    // The pairing path (recommended default in the
                    // UI). Unauthenticated pair POST, authenticated probe with
                    // strict device-credential capability check, persistence only
                    // after verification, exactly one best-effort self-revoke on
                    // every post-pair failure.
                    connectPairing = { profileName, baseUrl, pairingCode ->
                        productionDependencies.value.pairingCoordinator
                            .connect(profileName, baseUrl, pairingCode)
                    },
                    // A freshly verified server becomes the ACTIVE
                    // server. The selection is persisted FIRST (metadata-only
                    // write). The navigation to the verified server keeps the
                    // existing, verified behavior — the user must never be stuck
                    // on Onboarding after a successful connection — but a failed
                    // selection persist is never applied optimistically: the
                    // in-memory switch is the existing active-server switch pattern and the
                    // selection itself stays as it was on disk; the next
                    // startup deterministically falls back to the existing
                    // resolution. No error details are surfaced.
                    onConnected = { success ->
                        navHostCoroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    (profileAdmin
                                        ?: productionDependencies.value.profileAdmin)
                                        .selectActiveServer(success.profileId)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Selection persist failed: see the comment above.
                            }
                            // Persist the onboarding-confirmed
                            // approval capability for the verified profile
                            // (metadata only, no credential). Best-effort and
                            // independent of the selection persist above: a
                            // failure never blocks the already-verified
                            // navigation; the capability stays fail-closed
                            // (absent -> false) until the next onboarding.
                            try {
                                withContext(Dispatchers.IO) {
                                    productionDependencies.value.capabilityStore.write(
                                        success.profileId,
                                        success.sessionChatInteractiveApprovals
                                    )
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Capability persist failed: stays fail-closed.
                            }
                            // The persist work above resumes off the main
                            // thread (e.g. under the Compose test dispatcher),
                            // while popping the back stack updates the popped
                            // entries' lifecycle and must run on the main
                            // thread — same guard as [serverSelected].
                            withContext(Dispatchers.Main) {
                                activeProfileId = success.profileId
                                // After a successful connection the back stack
                                // must contain exactly ONE Home entry: the
                                // onboarding entry, the server management entry
                                // that opened it and every previous entry up to
                                // (and including) the graph start are removed.
                                // Back on Home then leaves the app,
                                // exactly as after a first start.
                                navController.navigate(routeOf(AppScreen.Home)) {
                                    popUpTo(routeOf(initialScreen)) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                )
            }
            composable(routeOf(AppScreen.Sessions)) {
                val activeRun by (runController ?: productionDependencies.value.runController)
                    .activeRun.collectAsState()
                SessionsScreen(
                    runningSessionId = activeRun?.takeIf { it.profileId == activeProfileId }?.sessionId,
                    pageSource = sessionPageSource
                        ?: productionDependencies.value.sessionPageSource,
                    profileId = activeProfileId.orEmpty(),
                    creator = sessionCreator
                        ?: productionDependencies.value.sessionCreator,
                    manager = sessionManager
                        ?: productionDependencies.value.sessionManager,
                    onSessionSelected = ::openChat,
                    onSessionCreated = { sessionId ->
                        pendingCreatedSessionId = sessionId
                    }
                )
            }
            composable(routeOf(AppScreen.Settings)) {
                SettingsScreen(
                    languageController = resolvedLanguageController,
                    themeController = settingsThemeController,
                    chatTextSizeController = resolvedChatTextSizeController,
                    screenProtectionController = resolvedScreenProtectionController,
                    materialYouController = resolvedMaterialYouController,
                    appLockController = resolvedAppLockController,
                    appAuthenticator = resolvedAppAuthenticator,
                    onOpenAbout = {
                        navController.navigate(routeOf(AppScreen.About)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(routeOf(AppScreen.Terminal)) {
                TerminalRoute(
                    profileId = activeProfileId.orEmpty(),
                    source = terminalSource ?: productionDependencies.value.terminalSource,
                    authenticator = resolvedAppAuthenticator,
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(
                route = FILES_ROUTE,
                arguments = listOf(navArgument(FILES_PATH_ARGUMENT) {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) { entry ->
                FilesRoute(
                    profileId = activeProfileId.orEmpty(),
                    source = filesSource ?: productionDependencies.value.filesSource,
                    startPath = entry.arguments?.getString(FILES_PATH_ARGUMENT).orEmpty(),
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(routeOf(AppScreen.Projects)) {
                ProjectsRoute(
                    profileId = activeProfileId.orEmpty(),
                    source = filesSource ?: productionDependencies.value.filesSource,
                    onOpenProject = { path -> navController.navigate(routeOf(AppScreen.Files(path))) },
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(routeOf(AppScreen.About)) {
                AboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(routeOf(AppScreen.Profiles)) {
                ServerManagement(
                    // Guard against popping the back stack empty, exactly like the
                    // Chat back handler below: a fast double tap on "Zurück" must
                    // never pop the root entry and leave the NavHost without a
                    // destination.
                    onBack = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable(
                route = CHAT_ROUTE,
                arguments = listOf(navArgument(CHAT_ARGUMENT) { type = NavType.StringType })
            ) { entry ->
                val encodedSessionId = entry.arguments?.getString(CHAT_ARGUMENT).orEmpty()
                val decodedSessionId = decodeRouteArgument(encodedSessionId)
                // Presentation only: the header's "Hermes · <server>" line.
                // Metadata read (no credential); a failure just hides it.
                val chatServerName by produceState<String?>(null, activeProfileId) {
                    value = try {
                        withContext(Dispatchers.IO) {
                            (profileAdmin ?: productionDependencies.value.profileAdmin)
                                .listProfiles()
                                .firstOrNull { it.id == activeProfileId }
                                ?.profileName
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                ChatScreen(
                    serverName = chatServerName,
                    historySource = messageHistorySource
                        ?: productionDependencies.value.messageHistorySource,
                    runController = runController
                        ?: productionDependencies.value.runController,
                    runStopRequester = runStopRequester
                        ?: productionDependencies.value.runStopRequester,
                    runSteerRequester = runSteerRequester
                        ?: productionDependencies.value.runSteerRequester,
                    chatDraftRepository = chatDraftRepository
                        ?: productionDependencies.value.chatDraftRepository,
                    runApprovalRequester = runApprovalRequester
                        ?: productionDependencies.value.runApprovalRequester,
                    runStatusRequester = runStatusRequester
                        ?: productionDependencies.value.runStatusRequester,
                    runUnknownResolver = productionDependencies.value.runUnknownResolver,
                    sessionManager = sessionManager ?: productionDependencies.value.sessionManager,
                    notificationPermissionStore = productionDependencies.value.notificationPermissionStore,
                    attachmentSource = productionDependencies.value.chatAttachmentSource,
                    chatTextSizeController = resolvedChatTextSizeController,
                    profileId = activeProfileId.orEmpty(),
                    sessionId = decodedSessionId,
                    sessionTitle = activeSessionTitle
                        .takeIf { activeSessionTitleSessionId == decodedSessionId },
                    onBack = {
                        // Guard against popping the back stack empty: a double
                        // back event (fast double-tap on "Zurück" or a spurious
                        // input event, e.g. from an accessibility service while
                        // the tree changes) must never leave the NavHost without
                        // a destination — that renders an empty screen whose
                        // BackHandler is gone, so the next back closes the task.
                        // Only an entry with a predecessor is popped. Comparing
                        // with the start destination is not enough: a successful
                        // onboarding removes the start entry (popUpTo inclusive),
                        // so the remaining root can be Sessions while the start
                        // destination is Onboarding.
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    }
                )
            }
        }
    }
}


internal fun routeOf(screen: AppScreen): String = when (screen) {
    AppScreen.Welcome -> "welcome"
    AppScreen.Onboarding -> "onboarding"
    AppScreen.Home -> "home"
    AppScreen.Server -> "server"
    AppScreen.Activity -> "activity"
    AppScreen.Sessions -> "sessions"
    is AppScreen.Chat -> "chat/${encodeRouteArgument(screen.sessionId)}"
    AppScreen.Profiles -> "profiles"
    AppScreen.Settings -> "settings"
    AppScreen.About -> "about"
    AppScreen.Terminal -> "terminal"
    is AppScreen.Files -> "files?$FILES_PATH_ARGUMENT=${encodeRouteArgument(screen.path)}"
    AppScreen.Projects -> "projects"
}

internal const val FILES_PATH_ARGUMENT = "path"
internal const val FILES_ROUTE = "files?$FILES_PATH_ARGUMENT={$FILES_PATH_ARGUMENT}"

internal const val CHAT_ARGUMENT = "sessionId"
internal const val CHAT_ROUTE = "chat/{$CHAT_ARGUMENT}"

internal fun encodeRouteArgument(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun decodeRouteArgument(value: String): String = try {
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
} catch (_: IllegalArgumentException) {
    ""
}
