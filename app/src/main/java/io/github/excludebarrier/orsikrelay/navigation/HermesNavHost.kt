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
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftStore
import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.message.MessageHistoryRepository
import io.github.excludebarrier.orsikrelay.data.message.StreamingChatRepository
import io.github.excludebarrier.orsikrelay.data.run.RunStopRepository
import io.github.excludebarrier.orsikrelay.data.run.RunSteerRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionListRepository
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.network.HermesRunStopClient
import io.github.excludebarrier.orsikrelay.network.HermesRunSteerClient
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
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
    streamingChatSource: StreamingChatSource? = null,
    runStopRequester: RunStopRequester? = null,
    runSteerRequester: RunSteerRequester? = null,
    chatDraftRepository: ChatDraftRepository? = null,
    profileAdmin: ProfileAdmin? = null,
    themeController: ThemeModeController? = null,
    startupProfileResolver: (suspend () -> StartupProfile)? = null,
    selectionStore: ActiveServerSelectionStore? = null
) {
    val applicationContext = LocalContext.current.applicationContext
    val productionDependencies = remember(applicationContext) {
        lazy {
            val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
            val metadataStore = SharedPreferencesServerProfileMetadataStore(applicationContext)
            // Mission 012: one app-private selection store shared by the
            // repository (select/delete) and the startup resolver. It holds
            // exactly one profile ID — no credential, URL, draft or chat data.
            val selectionStore = selectionStore
                ?: SharedPreferencesActiveServerSelectionStore(applicationContext)
            // One production ChatDraftRepository instance shared by the chat
            // screen and the profile repository (Mission 009B): profile
            // deletion clears the drafts through this same instance, so no
            // second, independent store is created.
            val chatDraftRepository = SharedPreferencesChatDraftRepository(
                store = SharedPreferencesChatDraftStore(applicationContext)
            )
            val profileRepository = ServerProfileRepository(
                metadataStore = metadataStore,
                credentialStore = credentialStore,
                profileDraftClearer = chatDraftRepository,
                selectionStore = selectionStore
            )
            ProductionDependencies(
                profileAdmin = ServerProfileAdmin(profileRepository),
                coordinator = ProfileConnectionCoordinator(
                    connectionProbe = HermesConnectionClient(),
                    profileSaver = profileRepository
                ),
                sessionPageSource = SessionListRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore
                ),
                sessionCreator = SessionCreateRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore
                ),
                messageHistorySource = MessageHistoryRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore
                ),
                streamingChatSource = StreamingChatRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore
                ),
                runStopRequester = RunStopRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore,
                    runStopTransport = HermesRunStopClient()
                ),
                runSteerRequester = RunSteerRepository(
                    profileRepository = profileRepository,
                    credentialStore = credentialStore,
                    runSteerTransport = HermesRunSteerClient()
                ),
                chatDraftRepository = chatDraftRepository,
                selectionStore = selectionStore,
                startupResolver = {
                    // Mission 012: the startup resolution validates the
                    // persisted active server selection against the metadata
                    // AND credential stores; every invalid selection falls
                    // back to the existing deterministic resolution.
                    resolveStartupProfile(
                        metadataStore = metadataStore,
                        credentialStore = credentialStore,
                        selectionStore = selectionStore
                    )
                }
            )
        }
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
                connect = { draft ->
                    val coordinator = connectionCoordinator
                        ?: productionDependencies.value.coordinator
                    coordinator.connect(draft)
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
                chatSource = streamingChatSource
                    ?: productionDependencies.value.streamingChatSource,
                runStopRequester = runStopRequester
                    ?: productionDependencies.value.runStopRequester,
                runSteerRequester = runSteerRequester
                    ?: productionDependencies.value.runSteerRequester,
                chatDraftRepository = chatDraftRepository
                    ?: productionDependencies.value.chatDraftRepository,
                profileId = activeProfileId.orEmpty(),
                sessionId = decodeRouteArgument(encodedSessionId),
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private data class ProductionDependencies(
    val profileAdmin: ProfileAdmin,
    val coordinator: ProfileConnectionCoordinator,
    val sessionPageSource: SessionPageSource,
    val sessionCreator: SessionCreator,
    val messageHistorySource: MessageHistorySource,
    val streamingChatSource: StreamingChatSource,
    val runStopRequester: RunStopRequester,
    val runSteerRequester: RunSteerRequester,
    val chatDraftRepository: ChatDraftRepository,
    val selectionStore: ActiveServerSelectionStore,
    val startupResolver: () -> StartupProfile
)

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
