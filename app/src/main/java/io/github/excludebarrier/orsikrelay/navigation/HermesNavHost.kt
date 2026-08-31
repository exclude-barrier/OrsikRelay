package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen

/**
 * Navigation shell for the Mission 001 slice.
 *
 * The pure state machine in [AppNavigationState] defines the transitions
 * (Onboarding -> Sessions, back via system back key) and is covered by
 * unit tests; the Compose [NavHost] back stack mirrors it at runtime.
 */
@Composable
fun HermesNavHost(
    startDestination: AppScreen,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = routeOf(startDestination)
    ) {
        composable(routeOf(AppScreen.Onboarding)) {
            OnboardingScreen(
                onContinue = {
                    navController.navigate(routeOf(AppScreen.Sessions)) {
                        // Onboarding stays in the back stack so the system
                        // back key returns to it (AppNavigationState.goBack).
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
