package io.github.excludebarrier.orsikrelay.navigation

/**
 * Destination of the app shell. Kept as a sealed type so the initial
 * destination and navigation transitions stay explicit and testable.
 */
sealed interface AppScreen {
    data object Onboarding : AppScreen
    data object Home : AppScreen
    data object Sessions : AppScreen
    data object Server : AppScreen
    data object Activity : AppScreen
    data class Chat(val sessionId: String) : AppScreen
    data object Profiles : AppScreen
    data object Settings : AppScreen
    data object About : AppScreen
}

/** The bottom-navigation tabs, in bar order. Every other screen hides the bar. */
val BottomTabs: List<AppScreen> = listOf(AppScreen.Home, AppScreen.Sessions, AppScreen.Server, AppScreen.Activity)

/**
 * Pure navigation state machine.
 *
 * Rules:
 * - The app starts on [AppScreen.Onboarding].
 * - [goToHome] moves to Home (after a successful connection or startup).
 * - [goBack]: Home goes back to Onboarding (system back key); the other
 *   tabs and the screens opened from Home return to Home; a chat returns to
 *   the session list.
 * - Transitions are no-ops when already at the target, so the state is
 *   total and safe to drive from any event source.
 */
@JvmInline
value class AppNavigationState(val current: AppScreen) {

    fun goToHome(): AppNavigationState =
        if (current is AppScreen.Home) this else AppNavigationState(AppScreen.Home)

    fun goToSessions(): AppNavigationState =
        if (current is AppScreen.Sessions) this else AppNavigationState(AppScreen.Sessions)

    fun goBack(): AppNavigationState = when (current) {
        AppScreen.Onboarding -> this
        AppScreen.Home -> AppNavigationState(AppScreen.Onboarding)
        AppScreen.Sessions,
        AppScreen.Server,
        AppScreen.Activity,
        AppScreen.Profiles,
        AppScreen.Settings,
        AppScreen.About -> AppNavigationState(AppScreen.Home)
        is AppScreen.Chat -> AppNavigationState(AppScreen.Sessions)
    }
}

/** Initial destination of the app: Onboarding. */
fun initialDestination(): AppScreen = AppScreen.Onboarding
