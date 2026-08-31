package io.github.excludebarrier.orsikrelay.navigation

/**
 * Destination of the app shell. Kept as a sealed type so the initial
 * destination and navigation transitions stay explicit and testable.
 */
sealed interface AppScreen {
    data object Onboarding : AppScreen
    data object Sessions : AppScreen
}

/**
 * Pure navigation state machine for the Mission 001 slice.
 *
 * Rules:
 * - The app starts on [AppScreen.Onboarding].
 * - [goToSessions] moves from Onboarding to Sessions.
 * - [goBack] moves from Sessions back to Onboarding (system back key).
 * - Both transitions are no-ops when already at the target, so the state
 *   is total and safe to drive from any event source.
 */
@JvmInline
value class AppNavigationState(val current: AppScreen) {

    fun goToSessions(): AppNavigationState =
        if (current is AppScreen.Sessions) this else AppNavigationState(AppScreen.Sessions)

    fun goBack(): AppNavigationState =
        if (current is AppScreen.Onboarding) this else AppNavigationState(AppScreen.Onboarding)
}

/** Initial destination of the app: Onboarding. */
fun initialDestination(): AppScreen = AppScreen.Onboarding
