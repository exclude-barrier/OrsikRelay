package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The persisted active server selection must survive a
 * REAL Android activity recreation.
 *
 * This test drives the REAL production [MainActivity] (not a
 * [androidx.activity.ComponentActivity] and not a bare
 * `composeRule.setContent`) through
 * [androidx.test.core.app.ActivityScenario]. The Compose rule auto-launches
 * the real activity once at the start; the test then seeds the existing test
 * stores (metadata, credential, selection) with a single complete synthetic
 * server AND its active selection, and performs genuine
 * [androidx.test.core.app.ActivityScenario.recreate] calls. Each recreation
 * re-runs `onCreate`, so the PRODUCTION startup resolution reads the
 * persisted stores again — proving the selection survives the recreation and
 * the restored server's session list is shown directly, with onboarding never
 * appearing.
 *
 * No network is involved: the session list is served through the test-only
 * seam on [MainActivity] (a `null`-defaulted, process-wide fake). With the
 * seam unset the production path is byte-for-byte unchanged. Every value here
 * is synthetic; no real URL, credential or request is used.
 */
class ActiveServerSelectionRecreationUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        // The rule already auto-launched MainActivity BEFORE this method ran,
        // so the initial startup resolution already saw the clean stores
        // (no profile -> onboarding). Clear again defensively and reset the
        // seam so the test starts from a known-clean persisted state.
        clearAllProfiles()
        MainActivity.sessionPageSourceForTests = null
    }

    @After
    fun tearDown() {
        clearAllProfiles()
        MainActivity.sessionPageSourceForTests = null
    }

    private fun clearAllProfiles() {
        runCatching { profileRepository.list() }.getOrNull()?.forEach { profile ->
            runCatching { profileRepository.delete(profile.id) }
        }
        runCatching { selectionStore.clear() }
    }

    @Test
    fun persistedSelectionSurvivesARealActivityRecreation() {
        // Seed exactly what a previous run would have left behind: one
        // COMPLETE server profile AND that server as the persisted active
        // selection.
        profileRepository.saveVerified(draft())
        selectionStore.write(FIXED_ID)

        // Serve the session list through the test-only seam so no network
        // request is made; the seam is reset in setUp/tearDown.
        MainActivity.sessionPageSourceForTests = fakePageSource

        // A genuine Android activity recreation (onSaveInstanceState +
        // onCreate with a non-null savedInstanceState). This re-runs the
        // PRODUCTION startup resolution against the persisted stores.
        composeRule.activityRule.scenario.recreate()

        // After the recreation the selection is restored from disk: the
        // previously active server's sessions are shown directly and
        // onboarding never appears.
        assertSessionsRestored()

        // A second recreation proves the restoration is stable, not a
        // one-shot in-memory state.
        composeRule.activityRule.scenario.recreate()
        assertSessionsRestored()
    }

    private fun assertSessionsRestored() {
        // The restored server's sessions are displayed.
        waitForText(SESSION_TITLE)
        // ... and onboarding is never visible.
        assertTrue(
            "Onboarding must not be visible after recreation",
            composeRule.onAllNodesWithText(ONBOARDING_TITLE)
                .fetchSemanticsNodes().isEmpty()
        )
        // Sessions were queried under the restored (selected) server only.
        assertTrue(
            "Sessions must be queried under the restored server, " +
                "got: $profileIdsQueried",
            profileIdsQueried.isNotEmpty() &&
                profileIdsQueried.all { it == FIXED_ID }
        )
        // The Sessions screen (not onboarding) is the visible start screen.
        assertEquals(
            1,
            composeRule.onAllNodesWithText(SESSIONS_TITLE).fetchSemanticsNodes().size
        )
    }

    private val fakePageSource = SessionPageSource { profileId, _, _ ->
        if (profileId.isBlank()) {
            SessionListResult.Failure(SessionListError.MissingProfile)
        } else {
            profileIdsQueried += profileId
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = "session-recreation-fixture",
                            title = SESSION_TITLE,
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

    private fun draft() = ProfileDraft(
        profileName = "Fixture Recreation",
        baseUrl = BASE_URL,
        apiKey = SecretValue(SYNTHETIC_KEY)
    )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val FIXED_ID = "fixture-profile-recreation"
        const val BASE_URL = "https://recreation.local"
        const val SYNTHETIC_KEY = "fixture-key-recreation"
        const val SESSION_TITLE = "Session Recreation Fixture"
        const val SESSIONS_TITLE = "Sessions"
        const val ONBOARDING_TITLE = "Server hinzufügen"
    }
}
