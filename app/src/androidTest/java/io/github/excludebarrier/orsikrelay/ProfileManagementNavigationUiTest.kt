package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The profile management slice is reachable from the app shell
 * and a confirmed deletion returns to a safe state in which no chat keeps
 * living under the deleted profile. All fakes are synthetic; no network
 * request is made.
 */
class ProfileManagementNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val admin = FakeProfileAdmin(
        listOf(
            ServerProfile.create(PROFILE_A_ID, "Alpha", "https://alpha.local"),
            ServerProfile.create("profile-b", "Beta", "https://beta.local")
        )
    )

    @Test
    fun profileButtonOpensTheProfileListWithStoredProfiles() {
        setHost(startup = StartupProfile.Ready(PROFILE_A_ID))

        waitForText(context.getString(R.string.sessions_title))
        openProfilesFromOverflowMenu()

        waitForText(context.getString(R.string.profiles_title))
        composeRule.onNodeWithText("Alpha").assertExists()
        composeRule.onNodeWithText("alpha.local").assertExists()
        composeRule.onNodeWithText("Beta").assertExists()

        composeRule.onNodeWithText(context.getString(R.string.chat_back)).performClick()
        waitForText(context.getString(R.string.sessions_title))
    }

    @Test
    fun confirmedDeletionReturnsToSessionsWithoutAnyChatUnderTheDeletedProfile() {
        val profileIdsQueried = CopyOnWriteArrayList<String>()
        val sessions = SessionPageSource { profileId, _, _ ->
            if (profileId.isBlank()) {
                SessionListResult.Failure(SessionListError.MissingProfile)
            } else {
                profileIdsQueried += profileId
                SessionListResult.Success(
                    SessionPage(
                        sessions = listOf(
                            HermesSession(
                                id = "session-a",
                                title = "Session Alpha",
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
        setHost(startup = StartupProfile.Ready(PROFILE_A_ID), source = sessions)

        waitForText("Session Alpha")
        composeRule.onNodeWithText("Session Alpha").performClick()
        waitForText(context.getString(R.string.chat_title))

        composeRule.onNodeWithText(context.getString(R.string.chat_back)).performClick()
        waitForText("Session Alpha")
        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))

        openDeleteDialog(PROFILE_A_ID)
        waitForText(context.getString(R.string.profiles_delete_title))
        // Pre-deletion loads (Sessions + Chat) legitimately queried the
        // active profile; only post-deletion loads must not.
        val queriesBeforeDeletion = profileIdsQueried.size
        composeRule.onNodeWithText(context.getString(R.string.profiles_delete_confirm)).performClick()

        waitForText(context.getString(R.string.error_session_profile))
        // The safe state: no chat entry is alive any more.
        assertTrue(
            composeRule.onAllNodesWithText(context.getString(R.string.chat_title)).fetchSemanticsNodes().isEmpty()
        )
        assertEquals(1, admin.deletedCount)
        // Only Alpha (the active profile) was deleted; Beta survives.
        assertEquals(
            listOf(ServerProfile.create("profile-b", "Beta", "https://beta.local")),
            admin.listProfiles()
        )
        assertTrue(
            profileIdsQueried.drop(queriesBeforeDeletion).all { it != PROFILE_A_ID }
        )
    }

    @Test
    fun cancelKeepsTheProfileAndNoDeletionIsStarted() {
        setHost(startup = StartupProfile.Ready(PROFILE_A_ID))

        waitForText(context.getString(R.string.sessions_title))
        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))
        openDeleteDialog("profile-b")
        waitForText(context.getString(R.string.profiles_delete_title))
        composeRule.onNodeWithText(context.getString(R.string.new_session_cancel)).performClick()

        waitForText("Beta")
        composeRule.onNodeWithText("Alpha").assertExists()
        assertEquals(0, admin.deletedCount)
        assertEquals(2, admin.listProfiles().size)
    }

    @Test
    fun failedDeletionShowsTheGenericErrorAndKeepsTheProfileListed() {
        val failingAdmin = object : ProfileAdmin {
            override fun listProfiles(): List<ServerProfile> =
                listOf(ServerProfile.create(PROFILE_A_ID, "Alpha", "https://alpha.local"))

            override fun deleteProfile(profileId: String) {
                throw IllegalStateException("synthetic deletion failure")
            }

            // No-op selection persist in this test's fake admin.
            override fun selectActiveServer(profileId: String) = Unit

            // This test has no device profiles.
            override fun credentialKind(profileId: String): ProfileCredentialKind =
                ProfileCredentialKind.API_KEY
        }
        setHost(startup = StartupProfile.Ready(PROFILE_A_ID), admin = failingAdmin)

        waitForText(context.getString(R.string.sessions_title))
        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))
        openDeleteDialog(PROFILE_A_ID)
        waitForText(context.getString(R.string.profiles_delete_title))
        composeRule.onNodeWithText(context.getString(R.string.profiles_delete_confirm)).performClick()

        waitForText(context.getString(R.string.profiles_delete_error_generic))
        composeRule.onNodeWithText("Alpha").assertExists()
        // The retry action replaces the confirm label after the failure.
        composeRule.onNodeWithText(context.getString(R.string.sessions_retry)).assertExists()
    }

    private fun setHost(
        startup: StartupProfile,
        admin: ProfileAdmin = this.admin,
        source: SessionPageSource = SessionPageSource { _, _, _ ->
            SessionListResult.Success(SessionPage(emptyList(), hasMore = false))
        }
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

    private fun openDeleteDialog(profileId: String) {
        composeRule.onNodeWithTag("serverActions:$profileId").performClick()
        composeRule.onNodeWithTag("serverDelete:$profileId").performClick()
    }

    private fun openProfilesFromOverflowMenu() {
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_more_options)
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.profiles_manage)).performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
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

        // No-op selection persist in this test's fake admin.
        override fun selectActiveServer(profileId: String) = Unit

        // This test has no device profiles.
        override fun credentialKind(profileId: String): ProfileCredentialKind =
            ProfileCredentialKind.API_KEY
    }

    private companion object {
        const val PROFILE_A_ID = "profile-a"
    }
}
