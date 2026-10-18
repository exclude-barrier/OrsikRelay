package io.github.excludebarrier.orsikrelay.security.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-lock state machine: cold start, the 30-second background rule,
 * configuration changes and the disabled switch. Time comes from a mutable
 * counter, never from the device clock.
 */
class AppLockControllerTest {

    private var now = 1_000L
    private val persisted = mutableListOf<Boolean>()

    private fun controller(enabled: Boolean) = AppLockController(
        initiallyEnabled = enabled,
        persist = { persisted += it },
        elapsedRealtime = { now }
    )

    @Test
    fun coldStartWithTheLockEnabledStartsLocked() {
        assertTrue(controller(enabled = true).locked)
    }

    @Test
    fun coldStartWithTheLockDisabledStartsOpen() {
        assertFalse(controller(enabled = false).locked)
    }

    @Test
    fun backgroundShorterThanThirtySecondsStaysOpen() {
        val controller = unlockedController()

        controller.onActivityStopped()
        now += 29_999
        controller.onActivityStarted()

        assertFalse(controller.locked)
    }

    @Test
    fun backgroundOfThirtySecondsLocks() {
        val controller = unlockedController()

        controller.onActivityStopped()
        now += 30_000
        controller.onActivityStarted()

        assertTrue(controller.locked)
    }

    @Test
    fun rotationDoesNotLock() {
        val controller = unlockedController()

        controller.onActivityStopped()
        now += 500
        controller.onActivityStarted()

        assertFalse(controller.locked)
    }

    @Test
    fun disabledLockNeverLocksAfterALongBackground() {
        val controller = controller(enabled = false)

        controller.onActivityStopped()
        now += 120_000
        controller.onActivityStarted()

        assertFalse(controller.locked)
    }

    @Test
    fun firstStartWithoutAPriorStopKeepsTheColdStartState() {
        val controller = unlockedController()

        controller.onActivityStarted()

        assertFalse(controller.locked)
    }

    @Test
    fun disablingClearsTheLockAndPersistsOff() {
        val controller = controller(enabled = true)
        assertTrue(controller.locked)

        controller.setEnabled(false)

        assertFalse(controller.locked)
        assertFalse(controller.enabled)
        assertEquals(listOf(false), persisted)
    }

    @Test
    fun enablingPersistsOnWithoutLockingTheCurrentSession() {
        val controller = controller(enabled = false)

        controller.setEnabled(true)

        assertFalse(controller.locked)
        assertTrue(controller.enabled)
        assertEquals(listOf(true), persisted)
    }

    @Test
    fun unlockOpensWithoutChangingTheSwitch() {
        val controller = controller(enabled = true)

        controller.unlock()

        assertFalse(controller.locked)
        assertTrue(controller.enabled)
        assertTrue(persisted.isEmpty())
    }

    private fun unlockedController(): AppLockController =
        controller(enabled = true).also { it.unlock() }
}
