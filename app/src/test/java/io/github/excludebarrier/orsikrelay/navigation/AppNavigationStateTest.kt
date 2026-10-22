package io.github.excludebarrier.orsikrelay.navigation

import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the start destination and the bottom-bar tabs (no Android
 * runtime, no network, fully deterministic).
 */
class AppNavigationStateTest {
    @Test
    fun `the bottom bar lists home agents server and activity in order`() {
        assertEquals(
            listOf(AppScreen.Home, AppScreen.Sessions, AppScreen.Server, AppScreen.Activity),
            BottomTabs
        )
    }

    @Test
    fun `without a saved server the app starts on welcome`() {
        assertEquals(AppScreen.Welcome, startScreenFor(StartupProfile.None, pairingDeepLink = false))
    }

    @Test
    fun `a pairing deep link skips welcome and opens the onboarding form`() {
        assertEquals(AppScreen.Onboarding, startScreenFor(StartupProfile.None, pairingDeepLink = true))
    }

    @Test
    fun `a saved or unreadable server starts on home and never on welcome`() {
        assertEquals(AppScreen.Home, startScreenFor(StartupProfile.Ready("profile"), pairingDeepLink = false))
        assertEquals(AppScreen.Home, startScreenFor(StartupProfile.Ready("profile"), pairingDeepLink = true))
        assertEquals(AppScreen.Home, startScreenFor(StartupProfile.Unavailable, pairingDeepLink = false))
    }
}
