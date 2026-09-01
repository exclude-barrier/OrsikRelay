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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.session.SessionListRepository
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HermesNavHost(
    startDestination: AppScreen,
    navController: NavHostController = rememberNavController(),
    connectionCoordinator: ProfileConnectionCoordinator? = null,
    sessionPageSource: SessionPageSource? = null,
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
                startupResolver = {
                    resolveStartupProfile(metadataStore, credentialStore)
                }
            )
        }
    }

    var resolvedStart by remember(startDestination) { mutableStateOf<AppScreen?>(null) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }

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
                profileId = activeProfileId.orEmpty()
            )
        }
    }
}

private data class ProductionDependencies(
    val coordinator: ProfileConnectionCoordinator,
    val sessionPageSource: SessionPageSource,
    val startupResolver: () -> StartupProfile
)

internal fun routeOf(screen: AppScreen): String = when (screen) {
    AppScreen.Onboarding -> "onboarding"
    AppScreen.Sessions -> "sessions"
}
