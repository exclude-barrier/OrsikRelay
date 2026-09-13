package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectError
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mission 017B: the "Gerät trennen" (device disconnect) UI contract.
 *
 * - Only profiles whose NON-SECRET credential kind is
 *   [ProfileCredentialKind.DEVICE_CREDENTIAL] offer the action;
 *   API-key profiles never render it and can therefore never start a
 *   self-revoke.
 * - The action is two-step: the row button opens a dedicated
 *   confirmation dialog; only the dialog's confirm button runs the
 *   revocation. Outside the dialog, nothing happens.
 * - A confirmed disconnect calls the disconnect seam exactly once,
 *   removes the local profile through the verified deletion path and
 *   triggers the safe navigation hook. A remote failure deletes
 *   nothing locally and shows the fixed, generic German message.
 * - While a revocation is in flight the confirm button is disabled, so
 *   a double tap cannot start a second revocation.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDisconnectUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var admin: FakeProfileAdmin
    private var disconnectCalls = 0
    private var disconnectedProfiles = mutableListOf<String>()
    private var disconnectResult: DeviceDisconnectResult =
        DeviceDisconnectResult.Disconnected
    private var disconnectRelease: CountDownLatch? = null

    @Before
    fun reset() {
        admin = FakeProfileAdmin()
        disconnectCalls = 0
        disconnectedProfiles = mutableListOf()
        disconnectResult = DeviceDisconnectResult.Disconnected
        disconnectRelease = null
    }

    @Test
    fun onlyDeviceCredentialProfilesOfferTheDisconnectAction() {
        admin.profiles = listOf(
            profile("device-profile", "Geräteprofil"),
            profile("api-profile", "API-Profil")
        )
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        admin.kinds["api-profile"] = ProfileCredentialKind.API_KEY

        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").assertIsDisplayed()
        // The API-key profile never renders the action.
        composeRule.onNodeWithTag("disconnectDevice:api-profile").assertDoesNotExist()
    }

    @Test
    fun theRowActionOnlyOpensTheDialogWithoutCallingTheDisconnect() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").performClick()

        // Step 1: the confirmation dialog is open.
        composeRule.onNodeWithText("Gerät trennen?").assertIsDisplayed()
        // Step 2 (not yet): no revocation was started.
        assertEquals(0, disconnectCalls)
    }

    @Test
    fun dismissingTheDialogChangesNothingLocally() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").performClick()
        composeRule.onNodeWithTag("deviceDisconnectDismiss").performClick()

        assertEquals(0, disconnectCalls)
        assertTrue(admin.deleted.isEmpty())
        composeRule.onNodeWithText("Gerät trennen?").assertDoesNotExist()
        // The profile is still listed.
        composeRule.onNodeWithText("Geräteprofil").assertIsDisplayed()
    }

    @Test
    fun aConfirmedDisconnectRevokesExactlyOnceRemovesAndNavigates() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").performClick()
        composeRule.onNodeWithTag("deviceDisconnectConfirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { disconnectedProfiles.size == 1 }
        composeRule.waitForIdle()
        assertEquals(1, disconnectCalls)
        // The verified deletion path (the coordinator's admin.deleteProfile)
        // ran exactly once, and the safe navigation hook was triggered.
        assertEquals(listOf("device-profile"), admin.deleted)
        assertEquals(listOf("device-profile"), disconnectedProfiles)
        // The local profile is gone from the list.
        composeRule.onNodeWithText("Geräteprofil").assertDoesNotExist()
    }

    @Test
    fun aRemoteFailureDeletesNothingLocallyAndShowsTheClosedError() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").performClick()
        composeRule.onNodeWithTag("deviceDisconnectConfirm").performClick()

        waitForText("Das Gerät konnte nicht getrennt werden.")
        assertEquals(1, disconnectCalls)
        // Remote failure: NOTHING is deleted locally, no navigation hook.
        assertTrue(admin.deleted.isEmpty())
        assertTrue(disconnectedProfiles.isEmpty())
        // The local profile remains; the dialog stays open for a retry.
        composeRule.onNodeWithText("Geräteprofil").assertIsDisplayed()
        composeRule.onNodeWithText("Gerät trennen?").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOnConfirmCannotStartASecondRevocation() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        // The first revocation stays in flight until the test releases it.
        val release = CountDownLatch(1)
        disconnectRelease = release
        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").performClick()
        composeRule.onNodeWithTag("deviceDisconnectConfirm").performClick()

        waitForText("Wird getrennt …")
        // The confirm button is disabled while the revocation runs.
        composeRule.onNodeWithTag("deviceDisconnectConfirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("deviceDisconnectConfirm").performClick()

        release.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) { disconnectedProfiles.size == 1 }
        assertEquals("no second revocation", 1, disconnectCalls)
    }

    private fun setContent() {
        composeRule.setContent {
            ProfilesScreen(
                admin = admin,
                onBack = {},
                onProfileDeleted = {},
                onDeviceDisconnected = { disconnectedProfiles += it },
                disconnect = { profileId ->
                    // Models the production wiring: the coordinator runs
                    // exactly one revocation and, on an acknowledged
                    // answer, removes the local profile through the
                    // verified admin deletion path.
                    disconnectCalls += 1
                    disconnectRelease?.await(3, TimeUnit.SECONDS)
                    if (disconnectResult is DeviceDisconnectResult.Disconnected) {
                        admin.deleteProfile(profileId)
                    }
                    disconnectResult
                }
            )
        }
    }

    private fun waitForText(text: String) {
        // substring = true: the closed messages are asserted by their
        // distinctive prefix, not by the full sentence.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun profile(id: String, name: String) = ServerProfile.create(
        id = id,
        profileName = name,
        baseUrl = "https://example.invalid/p/$id"
    )

    /**
     * Test double for [ProfileAdmin]: returns the configured profiles and
     * non-secret credential kinds. It records deletions but never stores
     * any credential.
     */
    private class FakeProfileAdmin : ProfileAdmin {
        var profiles: List<ServerProfile> = emptyList()
        val kinds = mutableMapOf<String, ProfileCredentialKind>()
        val deleted = mutableListOf<String>()

        override fun listProfiles(): List<ServerProfile> = profiles

        override fun deleteProfile(profileId: String) {
            deleted += profileId
            profiles = profiles.filterNot { it.id == profileId }
            kinds.remove(profileId)
        }

        override fun selectActiveServer(profileId: String) = Unit

        override fun credentialKind(profileId: String) =
            kinds[profileId] ?: ProfileCredentialKind.API_KEY
    }
}
