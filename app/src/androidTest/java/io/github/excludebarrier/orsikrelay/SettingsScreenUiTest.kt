package io.github.excludebarrier.orsikrelay

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI contract for the settings screen: the language list is the shared
 * supported-language registry and every choice persists. The app version and
 * license live on the separate about screen.
 */
class SettingsScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private val languageStore = FakeLanguageStore()
    private val themeStore = FakeThemeStore()

    private class FakeLanguageStore : LanguageSettingStore {
        var value: String? = null
        var writes = 0

        override fun read(): String? = value

        override fun write(tag: String?) {
            writes++
            value = tag
        }
    }

    private class FakeThemeStore : ThemeSettingStore {
        var value: ThemeMode = ThemeMode.System

        override fun read(): ThemeMode = value

        override fun write(mode: ThemeMode) {
            value = mode
        }
    }

    @Test
    fun languageSectionOffersSystemAndEverySupportedLanguage() {
        setSettings()

        composeRule.onNodeWithTag("settings_language_system").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_en").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_de").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("Deutsch").assertIsDisplayed()
    }

    @Test
    fun selectingGermanPersistsTheTag() {
        setSettings()

        composeRule.onNodeWithTag("settings_language_de").performClick()

        assertEquals("de", languageStore.value)
        assertEquals(1, languageStore.writes)
    }

    @Test
    fun selectingEnglishPersistsTheTag() {
        setSettings()

        composeRule.onNodeWithTag("settings_language_en").performClick()

        assertEquals("en", languageStore.value)
    }

    @Test
    fun selectingSystemClearsTheStoredChoice() {
        languageStore.value = "de"
        setSettings()

        composeRule.onNodeWithTag("settings_language_system").performClick()

        assertNull(languageStore.value)
    }

    @Test
    fun backInvokesTheNavigationCallback() {
        var back = false
        setSettings(onBack = { back = true })

        composeRule.onNodeWithText(context.getString(R.string.settings_back)).performClick()

        assertTrue(back)
    }

    private fun setSettings(
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            val languageController = rememberLanguageController(languageStore) { }
            val themeController = rememberThemeModeController(themeStore)
            HermesTheme {
                SettingsScreen(
                    languageController = languageController,
                    themeController = themeController,
                    onBack = onBack
                )
            }
        }
        composeRule.waitForIdle()
    }
}
