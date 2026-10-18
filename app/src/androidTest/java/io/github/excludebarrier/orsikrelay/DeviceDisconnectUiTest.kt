package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * The unified removal UI contract of the server list.
 *
 * - There is exactly ONE removal action ("Löschen"); the former separate
 *   "Gerät trennen" row action no longer exists.
 * - For a [ProfileCredentialKind.DEVICE_CREDENTIAL] profile the confirmed
 *   removal runs the disconnect seam exactly once and removes the local
 *   profile only after the server acknowledged the revocation.
 * - A failed revocation (or a locally unloadable device access) deletes
 *   nothing, retries nothing automatically and offers the deliberate
 *   local-only fallback plus an explicit retry.
 * - API-key profiles keep the pure local deletion and never revoke.
 * - While a removal is in flight the dialog buttons are disabled.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDisconnectUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var admin: FakeProfileAdmin
    private var disconnectCalls = 0
    private var disconnectedProfiles = mutableListOf<String>()
    private var deletedProfiles = mutableListOf<String>()
    private var disconnectResult: DeviceDisconnectResult =
        DeviceDisconnectResult.Disconnected
    private var disconnectRelease: CountDownLatch? = null

    @Before
    fun reset() {
        admin = FakeProfileAdmin()
        disconnectCalls = 0
        disconnectedProfiles = mutableListOf()
        deletedProfiles = mutableListOf()
        disconnectResult = DeviceDisconnectResult.Disconnected
        disconnectRelease = null
    }

    @Test
    fun deviceProfilesHaveNoSeparateDisconnectRowAction() {
        admin.profiles = listOf(
            profile("device-profile", "Geräteprofil"),
            profile("api-profile", "API-Profil")
        )
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        admin.kinds["api-profile"] = ProfileCredentialKind.API_KEY

        setContent()

        composeRule.onNodeWithTag("disconnectDevice:device-profile").assertDoesNotExist()
        composeRule.onNodeWithTag("disconnectDevice:api-profile").assertDoesNotExist()
    }

    @Test
    fun theRowOpensTheUnifiedDeleteDialogWithoutDisconnecting() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        openDeleteDialog("device-profile")

        composeRule.onNodeWithText(context.getString(R.string.profiles_delete_title)).assertIsDisplayed()
        // The device-specific text states that the server comes first.
        composeRule
            .onNodeWithText(
                context.getString(R.string.profiles_delete_message_device, "Geräteprofil"),
                substring = true
            )
            .assertIsDisplayed()
        composeRule.onNodeWithTag("profileDeleteConfirm").assertIsDisplayed()
        assertEquals(0, disconnectCalls)
    }

    @Test
    fun dismissingTheDialogChangesNothingLocally() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteDismiss").performClick()

        assertEquals(0, disconnectCalls)
        assertTrue(admin.deleted.isEmpty())
        composeRule.onNodeWithText(context.getString(R.string.profiles_delete_title)).assertDoesNotExist()
        composeRule.onNodeWithText("Geräteprofil").assertIsDisplayed()
    }

    @Test
    fun aConfirmedDeleteRevokesExactlyOnceRemovesAndNavigates() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { disconnectedProfiles.size == 1 }
        composeRule.waitForIdle()
        assertEquals(1, disconnectCalls)
        // The verified deletion path (the coordinator's admin.deleteProfile)
        // ran exactly once, and the safe navigation hook was triggered.
        assertEquals(listOf("device-profile"), admin.deleted)
        assertEquals(listOf("device-profile"), disconnectedProfiles)
        composeRule.onNodeWithText("Geräteprofil").assertDoesNotExist()
    }

    @Test
    fun aRevokeFailureDeletesNothingAndShowsTheFallback() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        waitForText(context.getString(R.string.profiles_delete_revoke_error_generic))
        assertEquals(1, disconnectCalls)
        // Remote failure: NOTHING is deleted locally, no navigation hook.
        assertTrue(admin.deleted.isEmpty())
        assertTrue(disconnectedProfiles.isEmpty())
        assertTrue(deletedProfiles.isEmpty())
        // The warnings and the deliberate fallback are visible.
        composeRule
            .onNodeWithText(context.getString(R.string.profiles_delete_local_only_warning), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("profileDeleteLocalOnly").assertIsDisplayed()
        composeRule.onNodeWithTag("profileDeleteRetry").assertIsDisplayed()
        // The profile remains listed behind the open dialog.
        composeRule.onNodeWithText(context.getString(R.string.profiles_delete_title)).assertIsDisplayed()
    }

    @Test
    fun anInvalidStateShowsTheSameFallback() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState)
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        waitForText(context.getString(R.string.profiles_delete_revoke_error_generic))
        assertEquals(1, disconnectCalls)
        assertTrue(admin.deleted.isEmpty())
        composeRule.onNodeWithTag("profileDeleteLocalOnly").assertIsDisplayed()
    }

    @Test
    fun localOnlyRemovalDeletesLocallyWithoutAnotherRevocation() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()
        waitForText(context.getString(R.string.profiles_delete_revoke_error_generic))

        composeRule.onNodeWithTag("profileDeleteLocalOnly").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { deletedProfiles.size == 1 }
        composeRule.waitForIdle()
        assertEquals("no second revocation", 1, disconnectCalls)
        assertEquals(listOf("device-profile"), admin.deleted)
        assertTrue(disconnectedProfiles.isEmpty())
        composeRule.onNodeWithText("Geräteprofil").assertDoesNotExist()
    }

    @Test
    fun retryIssuesExactlyOneFurtherRevocation() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()
        waitForText(context.getString(R.string.profiles_delete_revoke_error_generic))
        assertEquals(1, disconnectCalls)

        disconnectResult = DeviceDisconnectResult.Disconnected
        composeRule.onNodeWithTag("profileDeleteRetry").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { disconnectedProfiles.size == 1 }
        composeRule.waitForIdle()
        assertEquals("exactly one further revocation", 2, disconnectCalls)
        assertEquals(listOf("device-profile"), admin.deleted)
    }

    @Test
    fun anApiKeyProfileIsRemovedLocallyWithoutAnyRevocation() {
        admin.profiles = listOf(profile("api-profile", "API-Profil"))
        admin.kinds["api-profile"] = ProfileCredentialKind.API_KEY
        setContent()

        openDeleteDialog("api-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) { deletedProfiles.size == 1 }
        composeRule.waitForIdle()
        assertEquals(0, disconnectCalls)
        assertEquals(listOf("api-profile"), admin.deleted)
        assertTrue(disconnectedProfiles.isEmpty())
        composeRule.onNodeWithText("API-Profil").assertDoesNotExist()
    }

    @Test
    fun aDeletionFailureKeepsTheGenericErrorMessage() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        // The revocation succeeded, the local cleanup failed.
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.DeletionFailed)
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        waitForText(context.getString(R.string.profiles_delete_error_generic))
        assertEquals(1, disconnectCalls)
        assertTrue(admin.deleted.isEmpty())
        composeRule.onNodeWithText("Geräteprofil").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOnConfirmCannotStartASecondRevocation() {
        admin.profiles = listOf(profile("device-profile", "Geräteprofil"))
        admin.kinds["device-profile"] = ProfileCredentialKind.DEVICE_CREDENTIAL
        val release = CountDownLatch(1)
        disconnectRelease = release
        setContent()

        openDeleteDialog("device-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        waitForText(context.getString(R.string.profiles_delete_disconnecting))
        composeRule.onNodeWithTag("profileDeleteConfirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        release.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) { disconnectedProfiles.size == 1 }
        assertEquals("no second revocation", 1, disconnectCalls)
    }

    @Test
    fun cancelSitsLeftOfTheDestructiveAction() {
        admin.profiles = listOf(profile("api-profile", "API-Profil"))
        admin.kinds["api-profile"] = ProfileCredentialKind.API_KEY
        setContent()

        openDeleteDialog("api-profile")

        val dismissBounds = composeRule.onNodeWithTag("profileDeleteDismiss").getBoundsInRoot()
        val confirmBounds = composeRule.onNodeWithTag("profileDeleteConfirm").getBoundsInRoot()
        assertTrue(
            "Abbrechen must sit left of the destructive action",
            dismissBounds.left < confirmBounds.left
        )
    }

    @Test
    fun anUnreadableCredentialKindTakesTheRevocationPathNotASilentLocalDelete() {
        admin.profiles = listOf(profile("unknown-profile", "Unbekanntes Profil"))
        admin.failingKinds += "unknown-profile"
        // Production re-checks the device metadata and fails closed.
        disconnectResult = DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState)
        setContent()

        openDeleteDialog("unknown-profile")
        composeRule.onNodeWithTag("profileDeleteConfirm").performClick()

        waitForText(context.getString(R.string.profiles_delete_revoke_error_generic))
        assertEquals(1, disconnectCalls)
        assertTrue("nothing may be deleted without the user's second action", admin.deleted.isEmpty())
        assertTrue(deletedProfiles.isEmpty())
        composeRule.onNodeWithTag("profileDeleteLocalOnly").assertIsDisplayed()
    }

    private fun openDeleteDialog(profileId: String) {
        composeRule.onNodeWithTag("serverActions:$profileId").performClick()
        composeRule.onNodeWithTag("serverDelete:$profileId").performClick()
    }

    private fun setContent() {
        composeRule.setContent {
            ProfilesScreen(
                admin = admin,
                onBack = {},
                onProfileDeleted = { deletedProfiles += it },
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
        val failingKinds = mutableSetOf<String>()

        override fun listProfiles(): List<ServerProfile> = profiles

        override fun deleteProfile(profileId: String) {
            deleted += profileId
            profiles = profiles.filterNot { it.id == profileId }
            kinds.remove(profileId)
        }

        override fun selectActiveServer(profileId: String) = Unit

        override fun credentialKind(profileId: String): ProfileCredentialKind {
            check(profileId !in failingKinds) { "metadata unavailable" }
            return kinds[profileId] ?: ProfileCredentialKind.API_KEY
        }
    }
}
