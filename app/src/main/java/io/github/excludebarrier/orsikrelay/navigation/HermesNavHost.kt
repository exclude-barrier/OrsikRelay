package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.message.MessageHistoryRepository
import io.github.excludebarrier.orsikrelay.data.message.StreamingChatRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionListRepository
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    themeController: ThemeModeController? = null,
    startupProfileResolver: (suspend () -> StartupProfile)? = null
) {
    val applicationContext = LocalContext.current.applicationContext
    val productionDependencies = remember(applicationContext) {
        lazy {
            val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
            val metadataStore = SharedPreferencesServerProfileMetadataStore(applicationContext)
            val profileRepository = ServerProfileRepository(metadataStore, credentialStore)
            ProductionDependencies(
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
                startupResolver = {
                    resolveStartupProfile(metadataStore, credentialStore)
                }
            )
        }
    }

    var resolvedStart by remember(startDestination) { mutableStateOf<AppScreen?>(null) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var pendingCreatedSessionId by remember { mutableStateOf<String?>(null) }

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
                onConnected = { success ->
                    activeProfileId = success.profileId
                    navController.navigate(routeOf(AppScreen.Sessions)) {
                        launchSingleTop = true
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
                profileId = activeProfileId.orEmpty(),
                sessionId = decodeRouteArgument(encodedSessionId),
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private data class ProductionDependencies(
    val coordinator: ProfileConnectionCoordinator,
    val sessionPageSource: SessionPageSource,
    val sessionCreator: SessionCreator,
    val messageHistorySource: MessageHistorySource,
    val streamingChatSource: StreamingChatSource,
    val startupResolver: () -> StartupProfile
)

internal fun routeOf(screen: AppScreen): String = when (screen) {
    AppScreen.Onboarding -> "onboarding"
    AppScreen.Sessions -> "sessions"
    is AppScreen.Chat -> "chat/${encodeRouteArgument(screen.sessionId)}"
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
