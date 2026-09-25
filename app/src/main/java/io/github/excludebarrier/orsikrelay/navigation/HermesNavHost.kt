package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.RunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.di.buildAppDependencies
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController
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
    runController: RunSessionController? = null,
    runStopRequester: RunStopRequester? = null,
    runSteerRequester: RunSteerRequester? = null,
    runApprovalRequester: RunApprovalRequester? = null,
    chatDraftRepository: ChatDraftRepository? = null,
    profileAdmin: ProfileAdmin? = null,
    themeController: ThemeModeController? = null,
    startupProfileResolver: (suspend () -> StartupProfile)? = null,
    selectionStore: ActiveServerSelectionStore? = null,
    capabilityStore: RunApprovalCapabilityStore? = null,
    // Product Gate: pairing deep-link bootstrap (server URL + code) pre-filled
    // into the onboarding route. Null when no deep link was received.
    pairingBootstrap: PairingBootstrap? = null,
    // P3: a validated notification tap (profileId, sessionId) to open
    // directly. Consumed exactly once; null when no tap is pending.
    notificationBootstrap: Pair<String, String>? = null
) {
    val applicationContext = LocalContext.current.applicationContext
    val productionDependencies = remember(applicationContext) {
        lazy { buildAppDependencies(applicationContext, selectionStore, capabilityStore) }
    }

    val navHostCoroutineScope = rememberCoroutineScope()
    var resolvedStart by remember(startDestination) { mutableStateOf<AppScreen?>(null) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var pendingCreatedSessionId by remember { mutableStateOf<String?>(null) }

    // Safe return after a profile deletion (Mission 010): the Profiles
    // destination is only reachable from Sessions, so popping everything
    // from the first Sessions entry upward (inclusive) removes the
    // Profiles entry AND every chat entry; the single new Sessions entry
    // carries no profile in its route and re-derives everything from
    // [activeProfileId]. Deleting the active profile resets the ID so the
    // Sessions screen falls into its existing generic incomplete-profile
    // state — no chat can keep living under the deleted profile.
    suspend fun profileDeleted(profileId: String) {
        // The caller invokes this from the deletion coroutine, which may
        // resume off the main thread (e.g. under the Compose test
        // dispatcher). NavController mutations and the state write below
        // must run on the main thread.
        withContext(Dispatchers.Main) {
            if (activeProfileId == profileId) {
                activeProfileId = ""
            }
            navController.navigate(routeOf(AppScreen.Sessions)) {
                popUpTo(routeOf(AppScreen.Sessions)) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Safe switch of the active server (Mission 011). Reuses exactly the
    // [profileDeleted] pattern, but sets [activeProfileId] to the chosen
    // server instead of clearing it. Popping from the first Sessions entry
    // upward (inclusive) removes the Profiles entry AND every existing Chat
    // destination, so no chat can keep living under the previous server; the
    // single new Sessions entry re-derives its list from the new ID. The
    // selection path itself reads no credential — only the (re)loaded
    // Sessions list does, under the new server.
    suspend fun serverSelected(profileId: String) {
        // The caller invokes this from the ProfilesScreen select button,
        // which runs on the main thread, but the NavController mutation and
        // the state write are still guarded the same way as [profileDeleted]
        // so the transition is always main-thread safe.
        withContext(Dispatchers.Main) {
            activeProfileId = profileId
            navController.navigate(routeOf(AppScreen.Sessions)) {
                popUpTo(routeOf(AppScreen.Sessions)) { inclusive = true }
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
            StartupProfile.None -> resolvedStart = AppScreen.Onboarding
            is StartupProfile.Ready -> {
                activeProfileId = startupProfile.profileId
                resolvedStart = AppScreen.Sessions
            }
            StartupProfile.Unavailable -> {
                activeProfileId = ""
                resolvedStart = AppScreen.Sessions
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

    // P3: a validated notification tap (profileId, sessionId) is consumed
    // exactly once. `pendingNotificationTap` mirrors the latest tap from the
    // activity (cold start via onCreate, warm start via onNewIntent) so the
    // consuming effect below can clear it after navigating.
    var pendingNotificationTap by remember { mutableStateOf(notificationBootstrap) }
    LaunchedEffect(notificationBootstrap) {
        if (notificationBootstrap != null) pendingNotificationTap = notificationBootstrap
    }
    // P3: open the chat targeted by a validated notification tap. The effect
    // waits until the navigation host is composed (resolvedStart non-null) and
    // then switches the active profile to the tap's server (so the chat
    // authenticates under the right server) and navigates to exactly that
    // chat. A revoked or missing credential fails closed in the chat's
    // existing history-load path; no navigation bypasses authentication.
    LaunchedEffect(resolvedStart, pendingNotificationTap) {
        if (resolvedStart == null) return@LaunchedEffect
        val bootstrap = pendingNotificationTap ?: return@LaunchedEffect
        val (profileId, sessionId) = bootstrap
        withContext(Dispatchers.Main) {
            activeProfileId = profileId
            navController.navigate(routeOf(AppScreen.Chat(sessionId))) {
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

    NavHost(
        navController = navController,
        startDestination = routeOf(initialScreen)
    ) {
        composable(routeOf(AppScreen.Onboarding)) {
            OnboardingRoute(
                pairingBootstrap = pairingBootstrap,
                connect = { draft ->
                    val coordinator = connectionCoordinator
                        ?: productionDependencies.value.coordinator
                    coordinator.connect(draft)
                },
                // Mission 017B: the pairing path (recommended default in the
                // UI). Unauthenticated pair POST, authenticated probe with
                // strict device-credential capability check, persistence only
                // after verification, exactly one best-effort self-revoke on
                // every post-pair failure.
                connectPairing = { profileName, baseUrl, pairingCode ->
                    productionDependencies.value.pairingCoordinator
                        .connect(profileName, baseUrl, pairingCode)
                },
                // Mission 012: a freshly verified server becomes the ACTIVE
                // server. The selection is persisted FIRST (metadata-only
                // write). The navigation to the verified server keeps the
                // existing, verified behavior — the user must never be stuck
                // on Onboarding after a successful connection — but a failed
                // selection persist is never applied optimistically: the
                // in-memory switch is the Mission 011 pattern and the
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
                        // Mission 014: persist the onboarding-confirmed
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
                        activeProfileId = success.profileId
                        navController.navigate(routeOf(AppScreen.Sessions)) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(routeOf(AppScreen.Sessions)) {
            SessionsScreen(
                pageSource = sessionPageSource
                    ?: productionDependencies.value.sessionPageSource,
                profileId = activeProfileId.orEmpty(),
                creator = sessionCreator
                    ?: productionDependencies.value.sessionCreator,
                themeController = themeController,
                onSessionSelected = { sessionId, _ ->
                    navController.navigate(routeOf(AppScreen.Chat(sessionId))) {
                        launchSingleTop = true
                    }
                },
                onSessionCreated = { sessionId ->
                    pendingCreatedSessionId = sessionId
                },
                onOpenProfileManagement = {
                    navController.navigate(routeOf(AppScreen.Profiles))
                }
            )
        }
        composable(routeOf(AppScreen.Profiles)) {
            ProfilesScreen(
                admin = profileAdmin
                    ?: productionDependencies.value.profileAdmin,
                activeProfileId = activeProfileId,
                onBack = { navController.popBackStack() },
                onProfileDeleted = { profileId ->
                    navHostCoroutineScope.launch { profileDeleted(profileId) }
                },
                onServerSelected = { profileId ->
                    navHostCoroutineScope.launch { serverSelected(profileId) }
                },
                // Mission 017B: a confirmed, server-acknowledged disconnect
                // removes the local profile connection; the navigation is
                // exactly the verified profile-deletion path, so no chat or
                // session can keep living under the disconnected profile.
                onDeviceDisconnected = { profileId ->
                    navHostCoroutineScope.launch { profileDeleted(profileId) }
                },
                disconnect = { profileId ->
                    productionDependencies.value.deviceDisconnectCoordinator
                        .disconnect(profileId)
                }
            )
        }
        composable(
            route = CHAT_ROUTE,
            arguments = listOf(navArgument(CHAT_ARGUMENT) { type = NavType.StringType })
        ) { entry ->
            val encodedSessionId = entry.arguments?.getString(CHAT_ARGUMENT).orEmpty()
            ChatScreen(
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
                notificationPermissionStore = productionDependencies.value.notificationPermissionStore,
                profileId = activeProfileId.orEmpty(),
                sessionId = decodeRouteArgument(encodedSessionId),
                onBack = {
                    // Guard against popping the back stack empty: a double
                    // back event (fast double-tap on "Zurück" or a spurious
                    // input event, e.g. from an accessibility service while
                    // the tree changes) must never leave the NavHost without
                    // a destination — that renders an empty screen whose
                    // BackHandler is gone, so the next back closes the task.
                    // `currentBackStack` is a restricted navigation API, so
                    // the equivalent check compares the current destination
                    // with the start destination (the only entry that must
                    // never be popped).
                    if (navController.currentDestination?.route != routeOf(initialScreen)) {
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}

internal fun routeOf(screen: AppScreen): String = when (screen) {
    AppScreen.Onboarding -> "onboarding"
    AppScreen.Sessions -> "sessions"
    is AppScreen.Chat -> "chat/${encodeRouteArgument(screen.sessionId)}"
    AppScreen.Profiles -> "profiles"
}

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
