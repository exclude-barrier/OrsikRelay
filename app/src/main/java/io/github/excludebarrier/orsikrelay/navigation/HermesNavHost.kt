package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen

@Composable
fun HermesNavHost(
    startDestination: AppScreen,
    navController: NavHostController = rememberNavController(),
    connectionCoordinator: ProfileConnectionCoordinator? = null
) {
    val applicationContext = LocalContext.current.applicationContext
    val productionCoordinator = remember(applicationContext) {
        lazy {
            val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
            val metadataStore = SharedPreferencesServerProfileMetadataStore(applicationContext)
            val repository = ServerProfileRepository(metadataStore, credentialStore)
            ProfileConnectionCoordinator(
                connectionProbe = HermesConnectionClient(),
                profileSaver = repository
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = routeOf(startDestination)
    ) {
        composable(routeOf(AppScreen.Onboarding)) {
            OnboardingRoute(
                connect = { draft ->
                    val activeCoordinator =
                        connectionCoordinator ?: productionCoordinator.value
                    activeCoordinator.connect(draft)
                },
                onConnected = {
                    navController.navigate(routeOf(AppScreen.Sessions)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(routeOf(AppScreen.Sessions)) {
            SessionsScreen()
        }
    }
}

internal fun routeOf(screen: AppScreen): String = when (screen) {
    AppScreen.Onboarding -> "onboarding"
    AppScreen.Sessions -> "sessions"
}
