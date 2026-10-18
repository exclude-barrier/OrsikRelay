package io.github.excludebarrier.orsikrelay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesLanguageSettingStore
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end language switch on the real [MainActivity].
 *
 * Seeds a synthetic active server, opens Settings from the session list and
 * selects Deutsch, English and System. On API 33+ the selection goes through
 * LocaleManager and the framework recreates the activity, so the session list
 * is asserted in the newly applied language. Every test resets the selection
 * to System afterwards.
 */
class LanguageSwitchUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var languagePreferences: android.content.SharedPreferences
    private lateinit var metadataStore: SharedPreferencesServerProfileMetadataStore
    private lateinit var credentialStore: AndroidKeystoreCredentialStore
    private lateinit var selectionStore: SharedPreferencesActiveServerSelectionStore
    private lateinit var profileRepository: ServerProfileRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        languagePreferences = context.getSharedPreferences(
            SharedPreferencesLanguageSettingStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        resetLanguage()
        clearAllProfiles()

        metadataStore = SharedPreferencesServerProfileMetadataStore(context)
        credentialStore = AndroidKeystoreCredentialStore(context)
        selectionStore = SharedPreferencesActiveServerSelectionStore(context)
        profileRepository = ServerProfileRepository(
            metadataStore = metadataStore,
            credentialStore = credentialStore,
            idFactory = { PROFILE_ID }
        )
        profileRepository.saveVerified(
            ProfileDraft(
                profileName = "Language Fixture",
                baseUrl = "https://language.local",
                apiKey = SecretValue("fixture-key-language")
            )
        )
        selectionStore.write(PROFILE_ID)
        MainActivity.sessionPageSourceForTests = fakePageSource
        composeRule.activityRule.scenario.recreate()
        waitForText(localizedString("en", R.string.sessions_refresh))
    }

    @After
    fun tearDown() {
        clearAllProfiles()
        MainActivity.sessionPageSourceForTests = null
        resetLanguage()
    }

    @Test
    fun germanEnglishAndSystemAreAppliedToTheRunningApp() {
        openSettings()
        selectLanguage("settings_language_de")
        awaitSettingsIn("de")
        composeRule.onNodeWithText(localizedString("de", R.string.settings_back)).performClick()
        waitForText(localizedString("de", R.string.sessions_refresh))
        assertEquals("de", SharedPreferencesLanguageSettingStore(context).read())

        openSettings()
        selectLanguage("settings_language_en")
        awaitSettingsIn("en")
        composeRule.onNodeWithText(localizedString("en", R.string.settings_back)).performClick()
        waitForText(localizedString("en", R.string.sessions_refresh))
        assertEquals("en", SharedPreferencesLanguageSettingStore(context).read())

        openSettings()
        selectLanguage("settings_language_system")
        awaitSettingsIn("en")
        composeRule.onNodeWithText(localizedString("en", R.string.settings_back)).performClick()
        waitForText(localizedString("en", R.string.sessions_refresh))
        assertNull(SharedPreferencesLanguageSettingStore(context).read())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            assertTrue(localeManager.applicationLocales.isEmpty)
        }
    }

    private fun awaitSettingsIn(languageTag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(
                    localizedString(languageTag, R.string.settings_title)
                ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun openSettings() {
        val languageTag = currentLanguageTag()
        val moreOptions = localizedString(languageTag, R.string.sessions_more_options)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(moreOptions).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(moreOptions).performClick()
        val entry = localizedString(languageTag, R.string.settings_entry)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(entry).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(entry).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectLanguage(itemTag: String) {
        composeRule.onNodeWithTag("settings_language_field").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(itemTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(itemTag).performClick()
        composeRule.waitForIdle()
    }

    private fun currentLanguageTag(): String {
        val stored = SharedPreferencesLanguageSettingStore(context).read()
        if (stored != null) return stored
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                .applicationLocales
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.language
                ?: "en"
        } else {
            "en"
        }
    }

    private fun localizedString(languageTag: String, id: Int): String {
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration).getString(id)
    }

    private fun resetLanguage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.setApplicationLocales(LocaleList.getEmptyLocaleList())
        }
        languagePreferences.edit().clear().commit()
    }

    private fun clearAllProfiles() {
        if (!::profileRepository.isInitialized) {
            profileRepository = ServerProfileRepository(
                metadataStore = SharedPreferencesServerProfileMetadataStore(context),
                credentialStore = AndroidKeystoreCredentialStore(context)
            )
        }
        runCatching { profileRepository.list() }.getOrNull()?.forEach { profile ->
            runCatching { profileRepository.delete(profile.id) }
        }
        runCatching {
            SharedPreferencesActiveServerSelectionStore(context).clear()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private val fakePageSource = SessionPageSource { profileId, _, _ ->
        SessionListResult.Success(
            SessionPage(
                sessions = listOf(
                    HermesSession(
                        id = "session-language-fixture",
                        title = "Language fixture session",
                        source = null,
                        lastActiveEpochSeconds = null,
                        messageCount = null,
                        pinned = false
                    )
                ),
                hasMore = false
            )
        )
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-language"
    }
}
