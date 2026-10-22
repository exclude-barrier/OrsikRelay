package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.excludebarrier.orsikrelay.ui.onboarding.WELCOME_SCREEN_TAG
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionStartupNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun simulatedRestartSelectsStoredProfileAndLoadsItsSessions() {
        val receivedProfile = AtomicReference<String>()
        val source = SessionPageSource { profileId, _, _ ->
            receivedProfile.set(profileId)
            listSuccess("Session nach Neustart")
        }
        setHost(source, startup = StartupProfile.Ready(PROFILE_ID))

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Session nach Neustart")

        assertEquals(PROFILE_ID, receivedProfile.get())
    }

    @Test
    fun noStoredProfileStartsOnWelcome() {
        setHost(
            source = SessionPageSource { _, _, _ -> listSuccess("Nicht sichtbar") },
            startup = StartupProfile.None
        )

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(WELCOME_SCREEN_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(WELCOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.onboarding_title)).assertCountEquals(0)
        composeRule.onAllNodesWithTag(HomeTags.SCREEN).assertCountEquals(0)
    }

    @Test
    fun incompleteStoredProfileShowsGenericProfileErrorWithoutCrash() {
        val source = SessionPageSource { profileId, _, _ ->
            if (profileId.isBlank()) {
                SessionListResult.Failure(SessionListError.MissingProfile)
            } else {
                listSuccess("Nicht sichtbar")
            }
        }
        // A metadata-only profile is still listed, so Home stays reachable;
        // only the agent list behind it has no credential to load.
        setHost(source, startup = StartupProfile.Unavailable, admin = incompleteProfileAdmin)

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText(context.getString(R.string.error_session_profile))
        composeRule.onNodeWithText(
            context.getString(R.string.error_session_profile)
        ).assertIsDisplayed()
    }


    private fun setHost(
        source: SessionPageSource,
        startup: StartupProfile,
        admin: ProfileAdmin? = null
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

    private val incompleteProfileAdmin = object : ProfileAdmin {
        override fun listProfiles(): List<ServerProfile> =
            listOf(ServerProfile.create(PROFILE_ID, "Incomplete", "https://incomplete.local"))

        override fun deleteProfile(profileId: String) = Unit

        override fun selectActiveServer(profileId: String) = Unit

        override fun credentialKind(profileId: String): ProfileCredentialKind =
            ProfileCredentialKind.API_KEY
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun listSuccess(title: String) = SessionListResult.Success(
        SessionPage(
            sessions = listOf(
                HermesSession(
                    id = "fixture-session-id",
                    title = title,
                    source = null,
                    lastActiveEpochSeconds = null,
                    messageCount = null,
                    pinned = false
                )
            ),
            hasMore = false
        )
    )

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
