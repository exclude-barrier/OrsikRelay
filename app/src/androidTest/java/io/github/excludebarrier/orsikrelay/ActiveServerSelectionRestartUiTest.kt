package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Mission 012 connected tests: the persisted active server selection.
 *
 * Part A (app restart) is driven through the REAL production wiring of
 * [HermesNavHost]: a restart is modeled the way the app actually boots,
 * i.e. a fresh composition whose startup resolution reads the persisted
 * stores. The production startup resolver runs against the real
 * [SharedPreferencesActiveServerSelectionStore],
 * [SharedPreferencesServerProfileMetadataStore] and
 * [AndroidKeystoreCredentialStore]; only the connection probe (no network
 * allowed) and the session list are fakes.
 *
 * - a valid persisted selection restores the chosen server directly,
 *   WITHOUT re-entering onboarding;
 * - a deleted/unknown selection and a selection without credential fall
 *   back to the existing safe startup state;
 * - the selection store itself never contains the API key, a URL or any
 *   other data.
 *
 * Part B (Mission 012 UI contract) drives the real [ProfilesScreen]
 * leaf-level: the active server switch must PERSIST first and trigger the
 * safe navigation switch only after the persist succeeded; a failed
 * persist never navigates and never applies an optimistic state change;
 * a double tap while a selection is in flight is ignored.
 */
class ActiveServerSelectionRestartUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var metadataStore: SharedPreferencesServerProfileMetadataStore
    private lateinit var credentialStore: AndroidKeystoreCredentialStore
    private lateinit var selectionStore: SharedPreferencesActiveServerSelectionStore
    private lateinit var profileRepository: ServerProfileRepository
    private val profileIdsQueried = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        metadataStore = SharedPreferencesServerProfileMetadataStore(context)
        credentialStore = AndroidKeystoreCredentialStore(context)
        selectionStore = SharedPreferencesActiveServerSelectionStore(context)
        profileRepository = ServerProfileRepository(
            metadataStore = metadataStore,
            credentialStore = credentialStore,
            idFactory = { FIXED_ID }
        )
        // Start from a clean persisted state (the default store names are
        // shared with the real app wiring, so every test clears its
        // fixtures).
        clearAllProfiles()
    }

    @After
    fun tearDown() {
        clearAllProfiles()
    }

    private fun clearAllProfiles() {
        runCatching { profileRepository.list() }.getOrNull()?.forEach { profile ->
            runCatching { profileRepository.delete(profile.id) }
        }
        runCatching { profileRepository.delete(GHOST_META_ID) }
        runCatching { selectionStore.clear() }
    }

    // -----------------------------------------------------------------
    // Part A: app restart through the production startup resolution.
    // -----------------------------------------------------------------

    @Test
    fun restartRestoresThePersistedActiveServerWithoutOnboarding() {
        // Pre-seed the persisted state a previous run would have left:
        // one complete server profile AND it is the persisted selection.
        profileRepository.saveVerified(draft())
        selectionStore.write(FIXED_ID)

        // A fresh composition (the "restart") resolves startup from the
        // persisted stores through the PRODUCTION resolver.
        setHost()

        // The persisted selection wins: the chosen server's sessions are
        // shown directly and onboarding never appears.
        waitForText("Session Fixture")
        assertTrue(
            composeRule.onAllNodesWithText("Server hinzufügen")
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Sessions must be queried under the restored server, " +
                "got: $profileIdsQueried",
            profileIdsQueried.isNotEmpty() &&
                profileIdsQueried.all { it == FIXED_ID }
        )

        // The selection store holds only the profile ID: no API key,
        // no URL, no profile name.
        val selectionPrefs = context.getSharedPreferences(
            SharedPreferencesActiveServerSelectionStore.DEFAULT_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        assertTrue(selectionPrefs.all.isNotEmpty())
        selectionPrefs.all.forEach { (key, value) ->
            val persisted = value.toString()
            assertFalse(
                "Selection key $key must not store the API key",
                persisted.contains(SYNTHETIC_KEY)
            )
            assertFalse(
                "Selection key $key must not store a URL",
                persisted.contains(BASE_URL)
            )
            assertFalse(
                "Selection key $key must not store a profile name",
                persisted.contains("Fixture")
            )
        }
    }

    @Test
    fun restartWithADeletedOrUnknownSelectionFallsBackToOnboarding() {
        // A selection pointing at a profile that no longer exists is
        // deterministically invalid; with no other profile stored, the
        // existing safe startup state is onboarding.
        selectionStore.write("ghost-profile-id")

        setHost()

        waitForText("Server hinzufügen")
    }

    @Test
    fun restartWithASelectionMissingItsCredentialFallsBackToTheGenericState() {
        // Metadata exists but the credential does not: the selection is
        // invalid AND the deterministic fallback finds no complete profile
        // -> the existing generic incomplete-profile state (no onboarding
        // loop, no crash, no details leaked).
        metadataStore.save(
            ServerProfile.create(GHOST_META_ID, "Ghost", "https://ghost.local")
        )
        selectionStore.write(GHOST_META_ID)

        setHost()

        waitForText("Der Server ist unvollständig oder nicht verfügbar.")
        assertTrue(
            composeRule.onAllNodesWithText("Server hinzufügen")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    // -----------------------------------------------------------------
    // Part B: Mission 012 UI contract (leaf-level, deterministic).
    // -----------------------------------------------------------------

    @Test
    fun aSuccessfulSelectPersistsFirstAndOnlyThenTriggersTheNavigation() {
        val admin = FakeSelectAdmin(events)
        setProfilesLeaf(admin)

        waitForText("Beta")
        composeRule.onNodeWithTag(selectTag(BETA_ID)).performClick()
        composeRule.waitForIdle()

        // Exactly the persist happened first and the navigation switch
        // second, in that order.
        assertEquals(
            listOf("select:$BETA_ID", "navigate:$BETA_ID"),
            events
        )
    }

    @Test
    fun aFailedSelectNeverTriggersTheNavigationAndKeepsThePreviousSelection() {
        val admin = FakeSelectAdmin(events, selectFails = true)
        setProfilesLeaf(admin)

        waitForText("Beta")
        composeRule.onNodeWithTag(selectTag(BETA_ID)).performClick()
        composeRule.waitForIdle()

        // The persist was attempted exactly once ...
        assertTrue(events.contains("select:$BETA_ID"))
        // ... but it failed, so the navigation switch was NEVER triggered.
        assertTrue(events.none { it.startsWith("navigate:") })
        // No optimistic state change: the previous server (Alpha) is still
        // the single active row, and Beta still offers the select action.
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Aktiver Server").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag(selectTag(BETA_ID)).assertExists()
    }

    @Test
    fun aSecondTapWhileASelectionIsInFlightIsIgnored() {
        val release = CountDownLatch(1)
        val admin = FakeSelectAdmin(events, blockRelease = release)
        setProfilesLeaf(admin)

        waitForText("Beta")
        // First tap starts the persist (in flight, the fake is blocked).
        composeRule.onNodeWithTag(selectTag(BETA_ID)).performClick()
        composeRule.waitForIdle()
        // While a selection is in flight the button is disabled, so a
        // second tap cannot start a second selection.
        composeRule.onNodeWithTag(selectTag(BETA_ID)).assertIsNotEnabled()

        release.countDown()
        composeRule.waitForIdle()

        // Exactly one selection ran, and it triggered the navigation once.
        assertEquals(
            listOf("select:$BETA_ID", "navigate:$BETA_ID"),
            events
        )
    }

    // -----------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------

    private val events = CopyOnWriteArrayList<String>()

    private fun selectTag(profileId: String) = "selectActiveServer:$profileId"

    private fun setProfilesLeaf(admin: ProfileAdmin) {
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(
                    admin = admin,
                    activeProfileId = ALPHA_ID,
                    onBack = {},
                    onServerSelected = { events += "navigate:$it" }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private class FakeSelectAdmin(
        private val events: CopyOnWriteArrayList<String>,
        private val selectFails: Boolean = false,
        private val blockRelease: CountDownLatch? = null
    ) : ProfileAdmin {
        override fun listProfiles(): List<ServerProfile> =
            listOf(
                ServerProfile.create(ALPHA_ID, "Alpha", "https://alpha.local"),
                ServerProfile.create(BETA_ID, "Beta", "https://beta.local")
            )

        override fun deleteProfile(profileId: String) = Unit

        override fun selectActiveServer(profileId: String) {
            blockRelease?.await(3, TimeUnit.SECONDS)
            events += "select:$profileId"
            if (selectFails) {
                throw IllegalStateException("synthetic selection store failure")
            }
        }

        // Mission 017B: the selection tests have no device profiles.
        override fun credentialKind(profileId: String): ProfileCredentialKind =
            ProfileCredentialKind.API_KEY
    }

    private fun draft() = ProfileDraft(
        profileName = "Fixture",
        baseUrl = BASE_URL,
        apiKey = SecretValue(SYNTHETIC_KEY)
    )

    private fun setHost() {
        val probe = ConnectionProbe {
            HermesConnectionResult.Success(
                version = "1",
                sessionsSupported = true,
                sessionStreamingSupported = true,
                sessionChatInteractiveApprovals = true
            )
        }
        val coordinator = ProfileConnectionCoordinator(
            connectionProbe = probe,
            profileSaver = profileRepository
        )
        val source = SessionPageSource { profileId, _, _ ->
            if (profileId.isBlank()) {
                SessionListResult.Failure(SessionListError.MissingProfile)
            } else {
                profileIdsQueried += profileId
                SessionListResult.Success(
                    SessionPage(
                        sessions = listOf(
                            HermesSession(
                                id = "session-fixture",
                                title = "Session Fixture",
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
        }
        // profileAdmin and startupProfileResolver are deliberately NOT
        // injected: the production wiring (real repository, real admin,
        // real startup resolver) runs against the default stores.
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    connectionCoordinator = coordinator,
                    sessionPageSource = source
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val FIXED_ID = "fixture-profile-restart"
        const val GHOST_META_ID = "fixture-profile-ghost-meta"
        const val ALPHA_ID = "profile-a"
        const val BETA_ID = "profile-b"
        const val BASE_URL = "https://restart.local"
        const val SYNTHETIC_KEY = "fixture-key-restart"
    }
}
