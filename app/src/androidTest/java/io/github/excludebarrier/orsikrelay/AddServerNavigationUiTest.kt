package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.profile.RunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.data.profile.VerifiedProfileSaver
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Server hinzufügen" from the server management screen:
 *
 * - the bottom action opens the onboarding route WITH a "Zurück" action,
 * - going back returns to "Server verwalten" without saving anything,
 * - the first-start onboarding (no previous destination) shows no "Zurück",
 * - a successful (fake) connection lands on Sessions with exactly one
 *   Sessions entry in the back stack, so system back leaves the app.
 *
 * All fakes are synthetic; no network request and no production store is
 * touched.
 */
class AddServerNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val admin = FakeProfileAdmin(
        listOf(ServerProfile.create(ALPHA_ID, "Alpha", "https://alpha.local"))
    )
    private val savedNames = CopyOnWriteArrayList<String>()

    @Test
    fun addServerOpensOnboardingWithBackAndBackReturnsWithoutSaving() {
        setHost(startup = StartupProfile.Ready(ALPHA_ID), coordinator = null)

        waitForText("Session Alpha")
        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))

        composeRule.onNodeWithTag("addServerButton").performClick()
        waitForText(context.getString(R.string.onboarding_title))
        composeRule.onNodeWithTag("onboardingBack").assertExists()

        composeRule.onNodeWithTag("onboardingBack").performClick()
        waitForText(context.getString(R.string.profiles_title))

        assertTrue("nothing may be saved on cancel", savedNames.isEmpty())
        assertTrue("no server may be selected on cancel", admin.selected.isEmpty())
    }

    @Test
    fun firstStartOnboardingShowsNoBack() {
        setHost(startup = StartupProfile.None, coordinator = null)

        waitForText(context.getString(R.string.onboarding_title))
        composeRule.onNodeWithTag("onboardingBack").assertDoesNotExist()
    }

    @Test
    fun successfulConnectLandsOnSessionsWithASingleBackStackEntry() {
        val coordinator = ProfileConnectionCoordinator(
            connectionProbe = ConnectionProbe {
                HermesConnectionResult.Success(
                    version = "1",
                    sessionsSupported = true,
                    sessionStreamingSupported = true,
                    sessionChatInteractiveApprovals = true
                )
            },
            profileSaver = VerifiedProfileSaver { draft ->
                savedNames += draft.profileName
                ServerProfile.create(NEW_ID, draft.profileName, draft.baseUrl)
            }
        )
        setHost(startup = StartupProfile.Ready(ALPHA_ID), coordinator = coordinator)

        waitForText("Session Alpha")
        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))
        composeRule.onNodeWithTag("addServerButton").performClick()
        waitForText(context.getString(R.string.onboarding_title))

        composeRule.onNodeWithTag("onboardingModeApiKey").performClick()
        composeRule.onNodeWithTag("onboardingProfileName").performTextInput("Neu")
        composeRule.onNodeWithTag("onboardingBaseUrl").performTextInput("https://neu.local")
        composeRule.onNodeWithTag("onboardingApiKey").performTextInput(SYNTHETIC_KEY)
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()

        waitForText("Session Neu")
        assertEquals(listOf("Neu"), savedNames)
        assertEquals(listOf(NEW_ID), admin.selected)
        // The new server is the active one; neither Profiles nor Onboarding
        // survive in the back stack.
        composeRule.onNodeWithText(context.getString(R.string.profiles_title)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_title)).assertDoesNotExist()

        // Exactly one Sessions entry: system back leaves the app.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.isFinishing
        }
    }

    /**
     * After the first-start onboarding the start entry (Onboarding) is gone
     * and Sessions is the root. A fast double tap on a back button must pop
     * only once and never leave the NavHost without a destination.
     */
    @Test
    fun doubleBackAfterFirstStartNeverEmptiesTheBackStack() {
        val coordinator = ProfileConnectionCoordinator(
            connectionProbe = ConnectionProbe {
                HermesConnectionResult.Success(
                    version = "1",
                    sessionsSupported = true,
                    sessionStreamingSupported = true,
                    sessionChatInteractiveApprovals = true
                )
            },
            profileSaver = VerifiedProfileSaver { draft ->
                savedNames += draft.profileName
                ServerProfile.create(NEW_ID, draft.profileName, draft.baseUrl)
            }
        )
        setHost(startup = StartupProfile.None, coordinator = coordinator)

        waitForText(context.getString(R.string.onboarding_title))
        composeRule.onNodeWithTag("onboardingModeApiKey").performClick()
        composeRule.onNodeWithTag("onboardingProfileName").performTextInput("Neu")
        composeRule.onNodeWithTag("onboardingBaseUrl").performTextInput("https://neu.local")
        composeRule.onNodeWithTag("onboardingApiKey").performTextInput(SYNTHETIC_KEY)
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()
        waitForText("Session Neu")

        openProfilesFromOverflowMenu()
        waitForText(context.getString(R.string.profiles_title))
        // Both taps are delivered in the same UI frame, before any
        // recomposition: exactly what a fast double tap does.
        val backClick = composeRule.onNodeWithText(context.getString(R.string.chat_back))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action
        composeRule.runOnUiThread {
            backClick?.invoke()
            backClick?.invoke()
        }
        composeRule.waitForIdle()

        waitForText("Session Neu")
        assertFalse(composeRule.activity.isFinishing)
    }

    private fun setHost(
        startup: StartupProfile,
        coordinator: ProfileConnectionCoordinator?
    ) {
        val source = SessionPageSource { profileId, _, _ ->
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = "session-$profileId",
                            title = if (profileId == ALPHA_ID) "Session Alpha" else "Session Neu",
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
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    connectionCoordinator = coordinator,
                    sessionPageSource = source,
                    profileAdmin = admin,
                    startupProfileResolver = { startup },
                    capabilityStore = NoOpCapabilityStore
                )
            }
        }
    }

    private fun openProfilesFromOverflowMenu() {
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_more_options)
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.profiles_manage)).performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private class FakeProfileAdmin(
        private val profiles: List<ServerProfile>
    ) : ProfileAdmin {
        val selected = CopyOnWriteArrayList<String>()

        override fun listProfiles(): List<ServerProfile> = profiles

        override fun deleteProfile(profileId: String) = Unit

        override fun selectActiveServer(profileId: String) {
            selected += profileId
        }

        override fun credentialKind(profileId: String): ProfileCredentialKind =
            ProfileCredentialKind.API_KEY
    }

    private object NoOpCapabilityStore : RunApprovalCapabilityStore {
        override fun read(profileId: String): Boolean = false
        override fun write(profileId: String, enabled: Boolean) = Unit
        override fun clear(profileId: String) = Unit
    }

    private companion object {
        const val ALPHA_ID = "profile-a"
        const val NEW_ID = "profile-new"
        const val SYNTHETIC_KEY = "fixture-key-add-server"
    }
}
