package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.settings.ChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.ScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.rememberChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
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
 * The app-lock switch in the privacy section: disabled without a device
 * screen lock, and every change (on and off) only after a successful
 * authentication.
 */
class AppLockSettingsUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchIsDisabledAndExplainedWithoutAScreenLock() {
        val controller = AppLockController(initiallyEnabled = false, persist = {})
        val authenticator = FakeAppAuthenticator(available = false)

        setSettings(controller, authenticator)

        composeRule.onNodeWithTag("settings_app_lock").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_app_lock_unavailable")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_app_lock_unavailable))
            .assertIsDisplayed()
        assertFalse(controller.enabled)
    }

    @Test
    fun enablingWaitsForASuccessfulAuthentication() {
        val persisted = mutableListOf<Boolean>()
        val controller = AppLockController(
            initiallyEnabled = false,
            persist = { persisted += it }
        )
        val authenticator = FakeAppAuthenticator()
        setSettings(controller, authenticator)

        composeRule.onNodeWithTag("settings_app_lock").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, authenticator.callCount) }
        assertFalse(controller.enabled)
        assertTrue(persisted.isEmpty())

        composeRule.runOnUiThread { authenticator.complete(true) }
        composeRule.waitForIdle()

        assertTrue(controller.enabled)
        assertEquals(listOf(true), persisted)
        composeRule.onNodeWithTag("settings_app_lock").assertIsOn()
    }

    @Test
    fun cancellingTheEnablePromptLeavesTheSwitchOff() {
        val persisted = mutableListOf<Boolean>()
        val controller = AppLockController(
            initiallyEnabled = false,
            persist = { persisted += it }
        )
        val authenticator = FakeAppAuthenticator()
        setSettings(controller, authenticator)

        composeRule.onNodeWithTag("settings_app_lock").performScrollTo().performClick()
        composeRule.runOnUiThread { authenticator.complete(false) }
        composeRule.waitForIdle()

        assertFalse(controller.enabled)
        assertTrue(persisted.isEmpty())
        composeRule.onNodeWithTag("settings_app_lock").assertIsOff()
    }

    @Test
    fun disablingAlsoRequiresASuccessfulAuthentication() {
        val persisted = mutableListOf<Boolean>()
        val controller = AppLockController(
            initiallyEnabled = true,
            persist = { persisted += it }
        )
        val authenticator = FakeAppAuthenticator()
        setSettings(controller, authenticator)

        composeRule.onNodeWithTag("settings_app_lock").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("settings_app_lock").performClick()
        composeRule.runOnIdle { assertEquals(1, authenticator.callCount) }
        assertTrue(controller.enabled)

        composeRule.runOnUiThread { authenticator.complete(true) }
        composeRule.waitForIdle()

        assertFalse(controller.enabled)
        assertEquals(listOf(false), persisted)
        composeRule.onNodeWithTag("settings_app_lock").assertIsOff()
    }

    private fun setSettings(
        appLockController: AppLockController,
        appAuthenticator: FakeAppAuthenticator
    ) {
        composeRule.setContent {
            val languageController = rememberLanguageController(EmptyLanguageStore) { }
            val themeController = rememberThemeModeController(EmptyThemeStore)
            val chatTextSizeController = rememberChatTextSizeController(EmptyChatTextSizeStore)
            val screenProtectionController =
                rememberScreenProtectionController(EmptyScreenProtectionStore)
            HermesTheme {
                SettingsScreen(
                    languageController = languageController,
                    themeController = themeController,
                    chatTextSizeController = chatTextSizeController,
                    screenProtectionController = screenProtectionController,
                    appLockController = appLockController,
                    appAuthenticator = appAuthenticator,
                    onBack = {}
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

    private object EmptyScreenProtectionStore : ScreenProtectionSettingStore {
        override fun read(): Boolean = false
        override fun write(enabled: Boolean) = Unit
    }
}
