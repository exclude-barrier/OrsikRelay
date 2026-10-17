package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.di.AppDependencies
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One image picked through the REAL system Photo Picker
 * (`PickVisualMedia.ImageOnly`), sent as a content part through the bridge to
 * the official core with a vision-capable provider.
 *
 * The system picker is a different app, so this test does not drive it: it
 * taps the production attachment action and then waits (bounded) for the
 * attachment thumbnail; the host script picks the pre-seeded image in the
 * picker with `adb shell uiautomator dump` + `input tap` (docs/bridge-e2e.md
 * §12). The seeded image is a solid red PNG; the only content assertion is
 * that the assistant answer names the colour.
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeImageTurnE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dependencies: AppDependencies
    private var pairedProfileId: String? = null

    @Before
    fun pairProfileAndResetState() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)
        AppDependenciesHolder.resetForTest()
        dependencies = AppDependenciesHolder.get(context)
        dependencies.profileAdmin.listProfiles().forEach { dependencies.profileAdmin.deleteProfile(it.id) }
        dependencies.selectionStore.clear()
        val outcome = dependencies.pairingCoordinator.connect(PROFILE_NAME, baseUrl!!, pairingCode!!)
        assertTrue("expected DevicePairingOutcome.Success, got $outcome", outcome is DevicePairingOutcome.Success)
        val success = outcome as DevicePairingOutcome.Success
        pairedProfileId = success.profileId
        dependencies.profileAdmin.selectActiveServer(success.profileId)
        dependencies.capabilityStore.write(success.profileId, success.sessionChatInteractiveApprovals)
        dependencies.notificationPermissionStore.markRequested()
    }

    @After
    fun deletePairedProfileAndResetState() {
        if (this::dependencies.isInitialized) {
            try {
                pairedProfileId?.let { dependencies.profileAdmin.deleteProfile(it) }
            } catch (_: Exception) {
                // Best effort: never let the cleanup mask a test result.
            }
        }
        AppDependenciesHolder.resetForTest()
    }

    @Test
    fun pickedImageReachesTheVisionModel() {
        composeRule.activityRule.scenario.recreate()
        waitForText(context.getString(R.string.sessions_title))
        composeRule.onNodeWithContentDescription(context.getString(R.string.new_session_fab)).performClick()
        waitForText(context.getString(R.string.new_session_title))
        composeRule.onNodeWithText(context.getString(R.string.new_session_create)).performClick()
        waitForComposerField()

        // Opens the real system Photo Picker; the host script picks the image.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_ADD).performClick()
        // While the picker is in front our activity has no compose hierarchy,
        // which the semantics query reports by throwing: that means "not yet".
        composeRule.waitUntil(timeoutMillis = PICKER_TIMEOUT_MS) {
            runCatching { tagPresent(TAG_ATTACHMENT_THUMBNAIL) }.getOrDefault(false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(COMPOSER_LABEL).performTextInput(QUESTION)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(SEND_LABEL).assertIsEnabled()
        composeRule.onNodeWithText(SEND_LABEL).performClick()

        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            (composerFieldEnabled() && !tagPresent(TAG_RUN_STOP)) || tagPresent(TAG_RUN_UNCERTAIN)
        }
        composeRule.waitForIdle()
        assertFalse("the image turn must settle terminal", tagPresent(TAG_RUN_UNCERTAIN))
        assertFalse("the attachment must have been consumed by the send", tagPresent(TAG_ATTACHMENT_THUMBNAIL))

        // The only content check: the vision model names the solid colour.
        val answers = composeRule.onAllNodesWithText("rot", substring = true, ignoreCase = true)
            .fetchSemanticsNodes()
            .filterNot { it.config.contains(SemanticsProperties.EditableText) }
            .filterNot { node ->
                node.config.contains(SemanticsProperties.Text) &&
                    node.config[SemanticsProperties.Text].any { it.text.contains("Farbe") }
            }
        assertTrue("the assistant answer must name the colour red", answers.isNotEmpty())
    }

    private fun tagPresent(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun composerFieldEnabled(): Boolean =
        composeRule.onAllNodesWithText(COMPOSER_LABEL)
            .fetchSemanticsNodes()
            .any {
                it.config.contains(SemanticsProperties.EditableText) &&
                    !it.config.contains(SemanticsProperties.Disabled)
            }

    private fun waitForComposerField() {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(COMPOSER_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private companion object {
        const val PROFILE_NAME = "OR-10 Live Bridge"
        const val COMPOSER_LABEL = "Nachricht"
        const val SEND_LABEL = "Senden"
        const val QUESTION = "Welche Farbe hat das Bild? Antworte mit einem einzigen Wort."
        const val TAG_ATTACHMENT_ADD = "composer_attachment_add"
        const val TAG_ATTACHMENT_THUMBNAIL = "composer_attachment_thumbnail"
        const val TAG_RUN_STOP = "composer_run_stop"
        const val TAG_RUN_UNCERTAIN = "chat_run_uncertain"
        const val UI_TIMEOUT_MS = 30_000L
        const val PICKER_TIMEOUT_MS = 90_000L
        const val RUN_TIMEOUT_MS = 120_000L
    }
}
