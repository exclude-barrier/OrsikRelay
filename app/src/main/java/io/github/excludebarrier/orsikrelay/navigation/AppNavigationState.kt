package io.github.excludebarrier.orsikrelay.navigation

import io.github.excludebarrier.orsikrelay.data.session.StartupProfile

/**
 * Destination of the app shell. Kept as a sealed type so the initial
 * destination and navigation transitions stay explicit and testable.
 */
sealed interface AppScreen {
    data object Welcome : AppScreen
    data object Onboarding : AppScreen
    data object Home : AppScreen
    data object Sessions : AppScreen
    data object Server : AppScreen
    data object Activity : AppScreen
    data class Chat(val sessionId: String) : AppScreen
    data object Profiles : AppScreen
    data object Settings : AppScreen
    data object About : AppScreen
    data object Terminal : AppScreen
}

/** The bottom-navigation tabs, in bar order. Every other screen hides the bar. */
val BottomTabs: List<AppScreen> = listOf(AppScreen.Home, AppScreen.Sessions, AppScreen.Server, AppScreen.Activity)

/** Initial destination of the app: Onboarding. */
fun initialDestination(): AppScreen = AppScreen.Onboarding

/**
 * The first screen once the startup profile is known: Home for a saved
 * (or unreadable) server, otherwise Welcome - unless a pairing deep link
 * arrived, which goes straight to its pre-filled onboarding form.
 */
fun startScreenFor(profile: StartupProfile, pairingDeepLink: Boolean): AppScreen = when (profile) {
    StartupProfile.None -> if (pairingDeepLink) AppScreen.Onboarding else AppScreen.Welcome
    is StartupProfile.Ready, StartupProfile.Unavailable -> AppScreen.Home
}
