package io.github.excludebarrier.orsikrelay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.settings.ChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.MaterialYouSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.ScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.openAppNotificationSettings
import io.github.excludebarrier.orsikrelay.ui.settings.rememberChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberMaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI contract for the settings screen: the language choice is a dropdown over
 * the shared supported-language registry, every choice persists, and the
 * chat, notifications and privacy rows expose their documented behavior.
 */
class SettingsScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private val languageStore = FakeLanguageStore()
    private val themeStore = FakeThemeStore()
    private val chatTextSizeStore = FakeChatTextSizeStore()
    private val screenProtectionStore = FakeScreenProtectionStore()
    private val materialYouStore = FakeMaterialYouStore()

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

    private class FakeChatTextSizeStore : ChatTextSizeSettingStore {
        var value: ChatTextSize = ChatTextSize.Normal
        var writes = 0

        override fun read(): ChatTextSize = value

        override fun write(size: ChatTextSize) {
            writes++
            value = size
        }
    }

    private class FakeScreenProtectionStore : ScreenProtectionSettingStore {
        var value: Boolean = false
        var writes = 0

        override fun read(): Boolean = value

        override fun write(enabled: Boolean) {
            writes++
            value = enabled
        }
    }

    private class FakeMaterialYouStore : MaterialYouSettingStore {
        var value: Boolean = false
        var writes = 0

        override fun read(): Boolean = value

        override fun write(enabled: Boolean) {
            writes++
            value = enabled
        }
    }

    @Test
    fun languageSegmentsOfferSystemAndEverySupportedLanguage() {
        setSettings()

        composeRule.onNodeWithTag("settings_language_system").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_en").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_de").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("Deutsch").assertIsDisplayed()
    }

    @Test
    fun languageSegmentsShowTheCurrentSelection() {
        setSettings()

        composeRule.onNodeWithTag("settings_language_system").assertIsSelected()

        selectLanguage("settings_language_de")

        composeRule.onNodeWithTag("settings_language_de").assertIsSelected()
        composeRule.onNodeWithTag("settings_language_system").assertIsNotSelected()
    }

    @Test
    fun groupsKeepTheirOrder() {
        setSettings()

        val labels = listOf(
            R.string.settings_group_general,
            R.string.settings_appearance_section,
            R.string.settings_chat_section,
            R.string.settings_notifications_label,
            R.string.settings_privacy_section,
            R.string.settings_info_section
        ).map { context.getString(it) }
        // The screen scrolls as one column, so every label is composed; its
        // (unclipped) position tells the document order.
        val tops = labels.map { label -> composeRule.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        assertEquals(labels.size, tops.toSet().size)
    }

    @Test
    fun rowsAreDenseButReachableAndSegmentsAre38Dp() {
        setSettings()

        composeRule.onNodeWithTag("settings_notifications").performScrollTo().assertHeightIsAtLeast(52.dp)
        composeRule.onNodeWithTag("settings_theme_Light").assertHeightIsEqualTo(38.dp)
        composeRule.onNodeWithTag("settings_chat_text_normal").assertHeightIsEqualTo(38.dp)
        composeRule.onNodeWithTag("settings_language_de").assertHeightIsEqualTo(38.dp)
    }

    @Test
    fun theInfoGroupShowsVersionLicenseSourceAndBugReport() {
        val opened = mutableListOf<String>()
        setSettings(onAllowedLinkOpen = { opened += it })

        composeRule.onNodeWithTag("settings_about").performScrollTo().assertTextContains("9.9.9-test")
        composeRule.onNodeWithTag("settings_license").performScrollTo()
        composeRule.onNodeWithText(context.getString(R.string.settings_license_value)).assertIsDisplayed()
        composeRule.onNodeWithTag("settings_source_code").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_report_bug").performScrollTo().performClick()

        assertEquals("https://github.com/exclude-barrier/OrsikRelay", opened[0])
        assertTrue(opened[1].startsWith("https://github.com/exclude-barrier/OrsikRelay/issues/new"))
    }

    @Test
    fun selectingGermanPersistsTheTagOnce() {
        setSettings()

        selectLanguage("settings_language_de")

        assertEquals("de", languageStore.value)
        assertEquals(1, languageStore.writes)
    }

    @Test
    fun selectingEnglishPersistsTheTag() {
        setSettings()

        selectLanguage("settings_language_en")

        assertEquals("en", languageStore.value)
    }

    @Test
    fun selectingSystemClearsTheStoredChoice() {
        languageStore.value = "de"
        setSettings()

        selectLanguage("settings_language_system")

        assertNull(languageStore.value)
    }

    @Test
    fun chatTextSizeSelectionPersists() {
        setSettings()

        composeRule.onNodeWithTag("settings_chat_text_large").performScrollTo().performClick()

        assertEquals(ChatTextSize.Large, chatTextSizeStore.value)
        assertEquals(1, chatTextSizeStore.writes)
    }

    @Test
    fun screenProtectionSwitchTogglesAndPersists() {
        setSettings()

        composeRule.onNodeWithTag("settings_screen_protection")
            .performScrollTo()
            .performClick()

        assertTrue(screenProtectionStore.value)

        composeRule.onNodeWithTag("settings_screen_protection").performClick()

        assertFalse(screenProtectionStore.value)
    }

    @Test
    fun notificationsRowOpensTheAppNotificationSettingsIntent() {
        val started = mutableListOf<Intent>()
        setSettings(
            openNotificationSettings = { ctx ->
                openAppNotificationSettings(ctx) { started += it }
            }
        )

        composeRule.onNodeWithTag("settings_notifications").performScrollTo().performClick()

        assertEquals(1, started.size)
        val intent = started.single()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun notificationsRowFallsBackToAppDetailsWhenNoActivityHandlesIt() {
        val started = mutableListOf<Intent>()
        setSettings(
            openNotificationSettings = { ctx ->
                openAppNotificationSettings(ctx) { intent ->
                    started += intent
                    throw ActivityNotFoundException("no notification settings")
                }
            }
        )

        composeRule.onNodeWithTag("settings_notifications").performScrollTo().performClick()

        assertEquals(2, started.size)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            started.last().action
        )
        assertEquals("package:${context.packageName}", started.last().data.toString())
    }

    @Test
    fun newSectionsAreVisible() {
        setSettings()

        composeRule.onNodeWithText(context.getString(R.string.settings_chat_section))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_notifications")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_privacy_section))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun appearanceSegmentsPersistTheSelectedMode() {
        setSettings()

        composeRule.onNodeWithTag("settings_theme_Dark").performScrollTo().performClick()

        assertEquals(ThemeMode.Dark, themeStore.value)
    }

    @Test
    fun materialYouSwitchIsVisibleFromApi31AndPersists() {
        setSettings(sdkInt = 31)

        composeRule.onNodeWithTag("settings_material_you").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_material_you").performClick()

        assertTrue(materialYouStore.value)
        assertEquals(1, materialYouStore.writes)
    }

    @Test
    fun materialYouSwitchIsHiddenBelowApi31() {
        setSettings(sdkInt = 30)

        composeRule.onNodeWithTag("settings_material_you").assertDoesNotExist()
    }

    @Test
    fun backIconInvokesTheNavigationCallback() {
        var back = false
        setSettings(onBack = { back = true })

        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_back))
            .performClick()

        assertTrue(back)
    }

    private fun selectLanguage(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    private fun setSettings(
        onBack: () -> Unit = {},
        sdkInt: Int = 31,
        openNotificationSettings: ((Context) -> Unit)? = null,
        onAllowedLinkOpen: ((String) -> Unit)? = null
    ) {
        val opener: (Context) -> Unit = openNotificationSettings
            ?: { ctx -> openAppNotificationSettings(ctx) }
        composeRule.setContent {
            val languageController = rememberLanguageController(languageStore) { }
            val themeController = rememberThemeModeController(themeStore)
            val chatTextSizeController = rememberChatTextSizeController(chatTextSizeStore)
            val screenProtectionController =
                rememberScreenProtectionController(screenProtectionStore)
            val materialYouController = rememberMaterialYouController(materialYouStore)
            HermesTheme {
                SettingsScreen(
                    languageController = languageController,
                    themeController = themeController,
                    chatTextSizeController = chatTextSizeController,
                    screenProtectionController = screenProtectionController,
                    appLockController = AppLockController(
                        initiallyEnabled = false,
                        persist = {}
                    ),
                    materialYouController = materialYouController,
                    appAuthenticator = null,
                    onBack = onBack,
                    onOpenAbout = {},
                    sdkInt = sdkInt,
                    openNotificationSettings = opener,
                    versionName = "9.9.9-test",
                    onAllowedLinkOpen = onAllowedLinkOpen
                )
            }
        }
        composeRule.waitForIdle()
    }
}
