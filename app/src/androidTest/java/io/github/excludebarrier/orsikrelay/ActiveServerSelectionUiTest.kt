package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_TITLE
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Switching the active server from the management screen.
 *
 * The switch reuses the verified, safe `profileDeleted` navigation pattern
 * (main-thread navigation, a fresh Sessions destination,
 * `popUpTo(Sessions, inclusive=true)`, `launchSingleTop`) — it removes every
 * existing Chat destination so no chat can keep living under the previous
 * server, and it re-derives the Sessions list from the newly chosen
 * in-memory profile ID.
 *
 * The selection path reads only metadata (`listProfiles`) and never touches
 * the credential store: the sessions list and the (removed) chat screen are
 * the only paths that could read credentials, and this test injects a fake
 * sessions source while proving no Chat destination survives the switch.
 *
 * All fakes are synthetic; no network request and no credential read is made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveServerSelectionUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // The startup effect resolves the profile on `Dispatchers.IO` and then
    // writes `resolvedStart` / `activeProfileId`. With the default
    // UnconfinedTestDispatcher that post-I/O continuation resumes on the IO
    // worker, so the first NavHost composition registers its lifecycle
    // observer off the main thread ("Method addObserver must be called on the
    // main thread"). A confined StandardTestDispatcher queues the
    // continuation on the test scheduler, which waitForIdle/waitUntil pump
    // from the main thread (same convention as
    // ActiveServerSelectionRestartUiTest).
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    private val admin = FakeProfileAdmin(
        listOf(
            ServerProfile.create(ALPHA_ID, "Alpha", "https://alpha.local"),
            ServerProfile.create(BETA_ID, "Beta", "https://beta.local")
        )
    )

    @Test
    fun selectingAStoredServerSwitchesActiveServerRemovesChatAndReloadsSessions() {
        val profileIdsQueried = CopyOnWriteArrayList<String>()
        val source = sessionsSourceFor(profileIdsQueried)
        setHost(startup = StartupProfile.Ready(ALPHA_ID), source = source)

        // Active server is Alpha at startup: Home is the start page and its
        // sessions are shown in the Agents tab.
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Session Alpha")

        // Open a chat under Alpha so a Chat destination exists on the stack.
        composeRule.onNodeWithText("Session Alpha").performClick()
        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_back)).performClick()
        waitForText("Session Alpha")

        // Go to the management screen: the ACTIVE server (Alpha) is marked
        // with exactly one "Aktiver Server" marker, and only the NON-active
        // server (Beta) offers the "use as active server" action.
        openServerTab()
        waitForText(context.getString(R.string.profiles_title))
        assertExactlyOneActiveMarker()
        composeRule.onNodeWithTag(rowTag(ALPHA_ID)).assertIsNotEnabled()

        // Capture the queries issued before the switch so the post-switch
        // slice can be asserted in isolation.
        composeRule.waitForIdle()
        val queriesBeforeSwitch = profileIdsQueried.size
        composeRule.onNodeWithTag(rowTag(BETA_ID)).performClick()

        // The safe state: back on Home, no Chat destination survived the
        // switch. The chosen server's (Beta) sessions are shown in the
        // Agents tab, the previous server's (Alpha) sessions are gone.
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Session Beta")
        composeRule.onNodeWithText("Session Alpha").assertDoesNotExist()
        assertTrue(
            composeRule.onAllNodesWithTag(TAG_CHAT_TITLE).fetchSemanticsNodes().isEmpty()
        )

        // Re-open the management screen: the marker has MOVED to the chosen
        // server (Beta) and Beta now carries no select action.
        openServerTab()
        waitForText(context.getString(R.string.profiles_title))
        assertExactlyOneActiveMarker()
        composeRule.onNodeWithTag(rowTag(BETA_ID)).assertIsNotEnabled()

        // Every session query issued after the switch was for the chosen
        // server (Beta) — the removed chat never re-fetched under Alpha and
        // the selection path itself read no credentials.
        val queriesAfterSwitch = profileIdsQueried.drop(queriesBeforeSwitch)
        assertTrue(
            "Only the chosen server may be queried after the switch, " +
                "got: $queriesAfterSwitch",
            queriesAfterSwitch.isNotEmpty() && queriesAfterSwitch.all { it == BETA_ID }
        )
    }

    @Test
    fun returningFromProfilesWithoutSelectingKeepsTheActiveServer() {
        val profileIdsQueried = CopyOnWriteArrayList<String>()
        val source = sessionsSourceFor(profileIdsQueried)
        setHost(startup = StartupProfile.Ready(ALPHA_ID), source = source)

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Session Alpha")

        // Open the management screen and return without selecting anything.
        composeRule.openServerManagementFromHome()
        waitForText(context.getString(R.string.profiles_title))
        // Alpha is still the active, marked server; nothing was selected.
        assertExactlyOneActiveMarker()
        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_back))
            .performClick()
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Session Alpha")

        // Still on Sessions, still under Alpha; no switch happened.
        composeRule.onNodeWithText("Session Beta").assertDoesNotExist()
        assertTrue(
            composeRule.onAllNodesWithTag(TAG_CHAT_TITLE).fetchSemanticsNodes().isEmpty()
        )
        // No new server was queried: the switch never started.
        assertTrue(profileIdsQueried.all { it == ALPHA_ID })
    }

    private fun setHost(
        startup: StartupProfile,
        source: SessionPageSource,
        admin: ProfileAdmin = this.admin
    ) {
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = source,
                    profileAdmin = admin,
                    startupProfileResolver = { startup }
                )
            }
        }
    }

    /** Server management lives in the Server tab (the Agents list has no menu). */
    private fun openServerTab() {
        composeRule.onNodeWithTag("nav_tab_server").performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /**
     * The active chip text is only rendered on the Profiles screen and only
     * for the single active row.
     */
    private fun assertExactlyOneActiveMarker() {
        assertEquals(
            1,
            // Design v2 shows the badge in capitals.
            composeRule.onAllNodesWithText(
                context.getString(R.string.profiles_chip_active).uppercase(context.resources.configuration.locales[0])
            ).fetchSemanticsNodes().size
        )
    }

    private fun rowTag(profileId: String) = "serverRow:$profileId"

    private fun sessionsSourceFor(
        profileIdsQueried: CopyOnWriteArrayList<String>
    ): SessionPageSource {
        // Display names mirror the stored server names so the UI shows
        // "Session Alpha" / "Session Beta", not the raw profile IDs.
        val displayName = { id: String ->
            if (id == ALPHA_ID) "Alpha" else if (id == BETA_ID) "Beta" else id
        }
        return SessionPageSource { profileId, _, _ ->
            if (profileId.isBlank()) {
                SessionListResult.Failure(SessionListError.MissingProfile)
            } else {
                profileIdsQueried += profileId
                SessionListResult.Success(
                    SessionPage(
                        sessions = listOf(
                            HermesSession(
                                id = "session-$profileId",
                                title = "Session ${displayName(profileId)}",
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
    }

    private class FakeProfileAdmin(
        initialProfiles: List<ServerProfile>
    ) : ProfileAdmin {
        private val deleted = CopyOnWriteArrayList<String>()
        private val remaining = initialProfiles.toMutableList()

        val deletedCount: Int
            get() = deleted.size

        override fun listProfiles(): List<ServerProfile> = remaining.toList()

        override fun deleteProfile(profileId: String) {
            deleted += profileId
            remaining.removeAll { it.id == profileId }
        }

        // The fake selection store — metadata only, no secret.
        val selectedActive = CopyOnWriteArrayList<String>()

        override fun selectActiveServer(profileId: String) {
            selectedActive.clear()
            selectedActive += profileId
        }

        // The selection tests have no device profiles.
        override fun credentialKind(profileId: String): ProfileCredentialKind =
            ProfileCredentialKind.API_KEY
    }

    private companion object {
        const val ALPHA_ID = "profile-a"
        const val BETA_ID = "profile-b"
    }
}
