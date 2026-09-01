package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
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

        waitForText("Session nach Neustart")

        assertEquals(PROFILE_ID, receivedProfile.get())
    }

    @Test
    fun noStoredProfileKeepsOnboardingAsStart() {
        setHost(
            source = SessionPageSource { _, _, _ -> listSuccess("Nicht sichtbar") },
            startup = StartupProfile.None
        )

        waitForText("Hermes einrichten")
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()
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
        setHost(source, startup = StartupProfile.Unavailable)

        waitForText("Das Serverprofil ist unvollständig oder nicht verfügbar.")
        composeRule.onNodeWithText(
            "Das Serverprofil ist unvollständig oder nicht verfügbar."
        ).assertIsDisplayed()
    }


    private fun setHost(source: SessionPageSource, startup: StartupProfile) {
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = source,
                    startupProfileResolver = { startup }
                )
            }
        }
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
