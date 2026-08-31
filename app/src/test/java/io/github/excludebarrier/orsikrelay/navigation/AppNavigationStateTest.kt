package io.github.excludebarrier.orsikrelay.navigation

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
    fun `back moves from sessions to onboarding`() {
        val state = AppNavigationState(AppScreen.Sessions)

        val next = state.goBack()

        assertEquals(AppScreen.Onboarding, next.current)
    }

    @Test
    fun `back is a no-op when already on onboarding`() {
        val state = AppNavigationState(initialDestination())

        val next = state.goBack()

        assertEquals(state, next)
    }
}
