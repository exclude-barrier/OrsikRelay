package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Cold-start theme contract: the persisted [ThemeMode] must be applied on
 * the VERY FIRST composition pass, not only after an asynchronous
 * preference read. A forced Dark selection on a light system must not
 * first paint a light frame and then flip (the visible flash the review
 * flagged as a low-severity defect).
 *
 * The controller is observed on every composition pass; with a
 * synchronous initial read the first entry already equals the stored
 * mode and stays stable.
 */
class ThemeFirstFrameUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeThemeStore(initial: ThemeMode) : ThemeSettingStore {
        var value: ThemeMode = initial
        var reads = 0
        var writes = 0

        override fun read(): ThemeMode {
            reads++
            return value
        }

        override fun write(mode: ThemeMode) {
            writes++
            value = mode
        }
    }

    @Test
    fun persistedDarkModeIsAppliedOnTheFirstFrameWithoutAFlash() {
        val store = FakeThemeStore(ThemeMode.Dark)
        val observed = mutableListOf<ThemeMode>()

        composeRule.setContent {
            val controller = rememberThemeModeController(store)
            observed += controller.mode
        }
        composeRule.waitForIdle()

        assertTrue("controller was never composed", observed.isNotEmpty())
        // The very first composition pass must already resolve to the
        // stored Dark mode — no intermediate System/light frame.
        assertEquals(ThemeMode.Dark, observed.first())
        // And it must stay stable (no later flip).
        assertTrue(observed.all { it == ThemeMode.Dark })
    }

    @Test
    fun persistedLightModeIsAppliedOnTheFirstFrameWithoutAFlash() {
        val store = FakeThemeStore(ThemeMode.Light)
        val observed = mutableListOf<ThemeMode>()

        composeRule.setContent {
            val controller = rememberThemeModeController(store)
            observed += controller.mode
        }
        composeRule.waitForIdle()

        assertTrue("controller was never composed", observed.isNotEmpty())
        assertEquals(ThemeMode.Light, observed.first())
        assertTrue(observed.all { it == ThemeMode.Light })
    }
}
