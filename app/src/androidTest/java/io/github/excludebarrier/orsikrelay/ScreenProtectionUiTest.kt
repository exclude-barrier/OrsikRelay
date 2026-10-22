package io.github.excludebarrier.orsikrelay

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.excludebarrier.orsikrelay.data.settings.ChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.MaterialYouSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.ScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.settings.ApplyScreenProtection
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.rememberChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberMaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The screen-protection switch applies and removes FLAG_SECURE on the
 * hosting activity window immediately, without an activity recreation.
 */
class ScreenProtectionUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val screenProtectionStore = FakeScreenProtectionStore()

    private class FakeScreenProtectionStore : ScreenProtectionSettingStore {
        var value: Boolean = false
        var writes = 0

        override fun read(): Boolean = value

        override fun write(enabled: Boolean) {
            writes++
            value = enabled
        }
    }

    @Test
    fun switchSetsAndClearsTheSecureFlagOnTheActivityWindow() {
        setSettings()
        val window = composeRule.activity.window

        assertFalse(isSecure(window))

        composeRule.onNodeWithTag("settings_screen_protection").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { isSecure(window) }

        assertTrue(screenProtectionStore.value)
        assertEquals(1, screenProtectionStore.writes)

        composeRule.onNodeWithTag("settings_screen_protection").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !isSecure(window) }

        assertFalse(screenProtectionStore.value)
        assertEquals(2, screenProtectionStore.writes)
    }

    private fun isSecure(window: android.view.Window): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun setSettings() {
        composeRule.setContent {
            val languageController = rememberLanguageController(EmptyLanguageStore) { }
            val themeController = rememberThemeModeController(EmptyThemeStore)
            val chatTextSizeController = rememberChatTextSizeController(EmptyChatTextSizeStore)
            val screenProtectionController =
                rememberScreenProtectionController(screenProtectionStore)
            val materialYouController = rememberMaterialYouController(EmptyMaterialYouStore)
            ApplyScreenProtection(screenProtectionController)
            HermesTheme {
                SettingsScreen(
                    languageController = languageController,
                    themeController = themeController,
                    chatTextSizeController = chatTextSizeController,
                    screenProtectionController = screenProtectionController,
                    materialYouController = materialYouController,
                    appLockController = AppLockController(
                        initiallyEnabled = false,
                        persist = {}
                    ),
                    appAuthenticator = null,
                    onBack = {},
                    onOpenAbout = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    private object EmptyLanguageStore : LanguageSettingStore {
        override fun read(): String? = null
        override fun write(tag: String?) = Unit
    }

    private object EmptyThemeStore : ThemeSettingStore {
        override fun read(): ThemeMode = ThemeMode.System
        override fun write(mode: ThemeMode) = Unit
    }

    private object EmptyChatTextSizeStore : ChatTextSizeSettingStore {
        override fun read(): ChatTextSize = ChatTextSize.Normal
        override fun write(size: ChatTextSize) = Unit
    }

    private object EmptyMaterialYouStore : MaterialYouSettingStore {
        override fun read(): Boolean = false
        override fun write(enabled: Boolean) = Unit
    }
}
