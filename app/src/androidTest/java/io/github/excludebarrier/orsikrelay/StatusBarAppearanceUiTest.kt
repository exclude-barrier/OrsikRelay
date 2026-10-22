package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.theme.SharedPreferencesThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.bottomTabTag
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Design review 2026-10-22 reported dark status-bar icons on Home in dark
 * mode. This drives the REAL [MainActivity] with the app forced to Dark on a
 * light system and checks the window's icon appearance on Home and after a
 * tab round trip: the icons must be light (`isAppearanceLightStatusBars`
 * false) on every route.
 */
class StatusBarAppearanceUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var themeStore: SharedPreferencesThemeSettingStore
    private lateinit var selectionStore: SharedPreferencesActiveServerSelectionStore
    private lateinit var profileRepository: ServerProfileRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        themeStore = SharedPreferencesThemeSettingStore(context)
        selectionStore = SharedPreferencesActiveServerSelectionStore(context)
        profileRepository = ServerProfileRepository(
            metadataStore = SharedPreferencesServerProfileMetadataStore(context),
            credentialStore = AndroidKeystoreCredentialStore(context),
            idFactory = { FIXED_ID }
        )
        clearAllProfiles()
        MainActivity.sessionPageSourceForTests = SessionPageSource { _, _, _ ->
            SessionListResult.Success(SessionPage(sessions = emptyList(), hasMore = false))
        }
    }

    @After
    fun tearDown() {
        clearAllProfiles()
        themeStore.write(ThemeMode.System)
        MainActivity.sessionPageSourceForTests = null
    }

    private fun clearAllProfiles() {
        runCatching { profileRepository.list() }.getOrNull()?.forEach { profile ->
            runCatching { profileRepository.delete(profile.id) }
        }
        runCatching { selectionStore.clear() }
    }

    @Test
    fun forcedDarkThemeKeepsLightStatusBarIconsOnHomeAndAfterATabSwitch() {
        profileRepository.saveVerified(
            ProfileDraft(profileName = "Fixture Bars", baseUrl = BASE_URL, apiKey = SecretValue(SYNTHETIC_KEY))
        )
        selectionStore.write(FIXED_ID)
        themeStore.write(ThemeMode.Dark)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(HomeTags.SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertLightIcons("Home")

        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Sessions)).performClick()
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Home)).performClick()
        composeRule.waitForIdle()
        assertLightIcons("Home after a tab round trip")
    }

    private fun assertLightIcons(where: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse("$where: dark theme needs light status-bar icons", controller.isAppearanceLightStatusBars)
            assertFalse("$where: dark theme needs light navigation-bar icons", controller.isAppearanceLightNavigationBars)
        }
    }

    private companion object {
        const val FIXED_ID = "fixture-profile-bars"
        const val BASE_URL = "https://bars.local"
        const val SYNTHETIC_KEY = "fixture-key-bars"
    }
}
