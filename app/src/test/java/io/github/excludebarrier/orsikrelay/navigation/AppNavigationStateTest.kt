package io.github.excludebarrier.orsikrelay.navigation

import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure navigation state machine (no Android runtime,
 * no network, fully deterministic).
 */
class AppNavigationStateTest {

    @Test
    fun `initial destination is onboarding`() {
        val state = AppNavigationState(initialDestination())

        assertEquals(AppScreen.Onboarding, state.current)
    }

    @Test
    fun `go to sessions moves from onboarding to sessions`() {
        val state = AppNavigationState(initialDestination())

        val next = state.goToSessions()

        assertEquals(AppScreen.Sessions, next.current)
    }

    @Test
    fun `go to sessions is a no-op when already on sessions`() {
        val state = AppNavigationState(AppScreen.Sessions)

        val next = state.goToSessions()

        assertEquals(state, next)
    }

    @Test
    fun `go to home moves from onboarding to home and is idempotent`() {
        val home = AppNavigationState(initialDestination()).goToHome()

        assertEquals(AppScreen.Home, home.current)
        assertEquals(home, home.goToHome())
    }

    @Test
    fun `back moves from home to onboarding`() {
        val next = AppNavigationState(AppScreen.Home).goBack()

        assertEquals(AppScreen.Onboarding, next.current)
    }

    @Test
    fun `back from every other tab returns to home`() {
        listOf(AppScreen.Sessions, AppScreen.Server, AppScreen.Activity).forEach { tab ->
            assertEquals(AppNavigationState(AppScreen.Home), AppNavigationState(tab).goBack())
        }
    }

    @Test
    fun `back from a chat returns to the session list`() {
        val next = AppNavigationState(AppScreen.Chat("s1")).goBack()

        assertEquals(AppNavigationState(AppScreen.Sessions), next)
    }

    @Test
    fun `the bottom bar lists home agents server and activity in order`() {
        assertEquals(
            listOf(AppScreen.Home, AppScreen.Sessions, AppScreen.Server, AppScreen.Activity),
            BottomTabs
        )
    }

    @Test
    fun `back is a no-op when already on onboarding`() {
        val state = AppNavigationState(initialDestination())

        val next = state.goBack()

        assertEquals(state, next)
    }

    @Test
    fun `back moves from profiles to home`() {
        val state = AppNavigationState(AppScreen.Profiles)

        val next = state.goBack()

        assertEquals(AppNavigationState(AppScreen.Home), next)
    }

    @Test
    fun `back moves from settings to home`() {
        val state = AppNavigationState(AppScreen.Settings)

        val next = state.goBack()

        assertEquals(AppNavigationState(AppScreen.Home), next)
    }

    @Test
    fun `back moves from about to home`() {
        val state = AppNavigationState(AppScreen.About)

        val next = state.goBack()

        assertEquals(AppNavigationState(AppScreen.Home), next)
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

    @Test
    fun `back on welcome stays on welcome`() {
        assertEquals(AppScreen.Welcome, AppNavigationState(AppScreen.Welcome).goBack().current)
    }
}
