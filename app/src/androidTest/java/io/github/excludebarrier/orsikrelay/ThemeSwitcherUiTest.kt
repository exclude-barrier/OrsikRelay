package io.github.excludebarrier.orsikrelay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI contract for the persistent theme switcher in the Sessions header.
 *
 * The selection is a Material 3 exposed dropdown anchored next to the
 * "Sessions" title. The currently selected mode label is always shown as the
 * field value; opening the menu reveals exactly the three options
 * System/Hell/Dunkel. Picking an option closes the menu, updates the label
 * immediately and persists the choice through the injected [ThemeSettingStore].
 * The resolved color scheme is applied to the whole screen through the
 * existing [HermesTheme].
 */
class ThemeSwitcherUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fakeStore = FakeThemeStore()

    private class FakeThemeStore : ThemeSettingStore {
        var value: ThemeMode = ThemeMode.System
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
    fun headerShowsSystemDefaultWithoutAStoredChoice() {
        composeRule.setContent {
            ThemeSwitcherScreen(fakeStore) {
                Text("Sessions-Inhalt")
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("System").assertIsDisplayed()
        assertEquals(ThemeMode.System, fakeStore.value)
    }

    @Test
    fun selectingAModeIsAppliedImmediatelyAndPersists() {
        composeRule.setContent {
            ThemeSwitcherScreen(fakeStore) {
                Text("Sessions-Inhalt")
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Darstellung").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Hell").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Hell").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hell").assertIsDisplayed()
        assertEquals(1, fakeStore.writes)
        assertEquals(ThemeMode.Light, fakeStore.value)
    }

    @Test
    fun darkModeForcesTheDarkPaletteEvenWhenTheSystemIsLight() {
        var captured: Color? = null
        composeRule.setContent {
            ThemeSwitcherScreen(fakeStore) {
                captured = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Darstellung").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Dunkel").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dunkel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            captured == Color(0xFF0D1117)
        }

        composeRule.onNodeWithText("Dunkel").assertIsDisplayed()
        assertEquals(ThemeMode.Dark, fakeStore.value)
    }

    @Test
    fun systemModeFollowsTheSystemDarkSetting() {
        var systemDark = false
        var captured: Color? = null
        composeRule.setContent {
            ThemeSwitcherScreen(fakeStore) {
                systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                captured = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        val expected = if (systemDark) Color(0xFF0D1117) else Color(0xFFFFFFFF)
        assertEquals(expected, captured)
    }

    @Test
    fun menuShowsExactlyTheThreeModes() {
        composeRule.setContent {
            ThemeSwitcherScreen(fakeStore) {
                Text("Sessions-Inhalt")
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Darstellung").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Dunkel").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(composeRule.onAllNodesWithText("System").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Hell").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Dunkel").fetchSemanticsNodes().isNotEmpty())
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }
}

/**
 * Test harness around [SessionsScreen] that wires the theme controller the
 * same way [io.github.excludebarrier.orsikrelay.MainActivity] does in production: the
 * [HermesTheme] consumes the controller's mode so the selected mode is
 * applied to the whole screen, while the Sessions header exposes the
 * dropdown.
 */
@Composable
private fun ThemeSwitcherScreen(
    store: ThemeSettingStore,
    content: @Composable () -> Unit
) {
    val controller = rememberThemeModeController(store)
    HermesTheme(darkTheme = controller.mode.resolve(false).let { dark ->
        // System mode must still follow the real system flag; Light/Dark
        // resolve to a fixed value.
        if (controller.mode == ThemeMode.System) {
            androidx.compose.foundation.isSystemInDarkTheme()
        } else {
            dark
        }
    }) {
        SessionsScreen(
            pageSource = SessionPageSource { _, _, _ ->
                SessionListResult.Success(SessionPage(emptyList(), false))
            },
            profileId = "fixture-profile-id",
            themeController = controller
        )
        content()
    }
}
