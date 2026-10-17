package io.github.excludebarrier.orsikrelay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * UI contract for the persistent appearance switcher in the settings screen.
 *
 * The three modes are radio options in the Appearance section. The currently
 * selected mode is applied to the whole settings screen through
 * [HermesTheme]; picking an option updates the palette immediately and
 * persists the choice through the injected [ThemeSettingStore].
 */
class ThemeSwitcherUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private val fakeStore = FakeThemeStore()
    private val fakeLanguageStore = FakeLanguageStore()

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

    private class FakeLanguageStore : LanguageSettingStore {
        var value: String? = null

        override fun read(): String? = value

        override fun write(tag: String?) {
            value = tag
        }
    }

    @Test
    fun settingsShowsSystemDefaultWithoutAStoredChoice() {
        composeRule.setContent {
            SettingsHarness(fakeStore, fakeLanguageStore)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_theme_System").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_appearance_section))
            .assertIsDisplayed()
        assertEquals(ThemeMode.System, fakeStore.value)
    }

    @Test
    fun selectingAModeIsAppliedImmediatelyAndPersists() {
        composeRule.setContent {
            SettingsHarness(fakeStore, fakeLanguageStore)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_theme_Light").performClick()
        composeRule.waitForIdle()

        assertEquals(1, fakeStore.writes)
        assertEquals(ThemeMode.Light, fakeStore.value)
    }

    @Test
    fun darkModeForcesTheDarkPaletteEvenWhenTheSystemIsLight() {
        var captured: Color? = null
        composeRule.setContent {
            SettingsHarness(fakeStore, fakeLanguageStore) {
                captured = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_theme_Dark").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            captured == Color(0xFF0D1117)
        }

        assertEquals(ThemeMode.Dark, fakeStore.value)
    }

    @Test
    fun systemModeFollowsTheSystemDarkSetting() {
        var systemDark = false
        var captured: Color? = null
        composeRule.setContent {
            SettingsHarness(fakeStore, fakeLanguageStore) {
                systemDark = isSystemInDarkTheme()
                captured = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        val expected = if (systemDark) Color(0xFF0D1117) else Color(0xFFFFFFFF)
        assertEquals(expected, captured)
    }

    @Test
    fun appearanceSectionShowsExactlyTheThreeModes() {
        composeRule.setContent {
            SettingsHarness(fakeStore, fakeLanguageStore)
        }
        composeRule.waitForIdle()

        ThemeMode.entries.forEach { mode ->
            composeRule.onNodeWithTag("settings_theme_${mode.name}").assertIsDisplayed()
        }
    }
}

/**
 * Test harness around [SettingsScreen] that wires both controllers like
 * production does: [HermesTheme] consumes the theme controller's mode and the
 * screen exposes the language and appearance choices.
 */
@Composable
private fun SettingsHarness(
    themeStore: ThemeSettingStore,
    languageStore: LanguageSettingStore,
    content: @Composable () -> Unit = {}
) {
    val themeController = rememberThemeModeController(themeStore)
    val languageController = rememberLanguageController(languageStore) { }
    val dark = if (themeController.mode == ThemeMode.System) {
        isSystemInDarkTheme()
    } else {
        themeController.mode.resolve(false)
    }
    HermesTheme(darkTheme = dark) {
        SettingsScreen(
            languageController = languageController,
            themeController = themeController,
            onBack = {}
        )
        content()
    }
}
