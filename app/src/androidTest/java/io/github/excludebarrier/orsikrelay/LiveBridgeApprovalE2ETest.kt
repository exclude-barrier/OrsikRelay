package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.di.AppDependencies
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A REAL positive approval through the real chat UI.
 *
 * Drives the real [MainActivity] and production graph against the live bridge
 * in front of the official core with a model provider and `approvals.mode:
 * manual`. The agent is asked to run `rm -rf` on a fresh, NON-existent path
 * under /tmp: harmless, but it trips the core's dangerous-command pattern, so
 * the core escalates an `approval.request` that the bridge narrows to the
 * Mobile-v1 shape. Phase 1 answers with deny, phase 2 with once (allow).
 *
 * Asserted per phase: the production approval dialog appears, the chosen
 * button resolves it, the dialog disappears, the turn settles terminal (never
 * uncertain) and the message is shown exactly once. That the command ran
 * exactly once for `once` and never for `deny` is proven server-side by the
 * bridge probe (docs/bridge-security.md §4.1), not by this UI test.
 *
 * Requires instrumentation arguments liveBaseUrl and livePairingCode (one
 * fresh single-use code; one @Test only).
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeApprovalE2ETest {

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
        dependencies.profileAdmin.listProfiles().forEach {
            dependencies.profileAdmin.deleteProfile(it.id)
        }
        dependencies.selectionStore.clear()

        val outcome = dependencies.pairingCoordinator.connect(PROFILE_NAME, baseUrl!!, pairingCode!!)
        assertTrue("expected DevicePairingOutcome.Success, got $outcome", outcome is DevicePairingOutcome.Success)
        val success = outcome as DevicePairingOutcome.Success
        pairedProfileId = success.profileId
        // The approval UI is gated on the negotiated capability.
        assertTrue(
            "the live bridge must negotiate interactive approvals",
            success.sessionChatInteractiveApprovals
        )
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
    fun denyThenAllowARealDangerousToolRequest() {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        composeRule.waitForAgents()
        composeRule.onNodeWithContentDescription(NEW_SESSION_FAB).performClick()
        waitForText(NEW_SESSION_TITLE)
        composeRule.onNodeWithText(NEW_SESSION_CREATE).performClick()
        waitForComposerField()

        approvePhase(TAG_DENY)
        approvePhase(TAG_ALLOW)
    }

    private fun approvePhase(buttonTag: String) {
        val message = "OR-06 ${UUID.randomUUID()} - Führe im Terminal-Tool genau " +
            "`rm -rf /tmp/or06-ui-${UUID.randomUUID()}` aus und antworte danach nur mit: fertig"
        composeRule.onNodeWithText(COMPOSER_LABEL).performTextInput(message)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithContentDescription(SEND_LABEL).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SEND_LABEL).performClick()

        // The production dialog, identified by its own title and fixed text.
        val title = context.getString(R.string.approval_title)
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() ||
                uncertainPresent()
        }
        assertFalse("the approval must not end the turn uncertain", uncertainPresent())
        composeRule.onNodeWithText(context.getString(R.string.approval_body_request))
            .assertExists()

        composeRule.onNodeWithTag(buttonTag).performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }

        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            (composerFieldEnabled() && !stopControlPresent()) || uncertainPresent()
        }
        composeRule.waitForIdle()
        assertFalse("a resolved approval must settle the turn terminal", uncertainPresent())
        assertFalse(
            "the approval error must not be shown",
            composeRule.onAllNodesWithText(context.getString(R.string.approval_error_generic))
                .fetchSemanticsNodes().isNotEmpty()
        )

        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(message) and !hasSetTextAction())
        composeRule.waitForIdle()
        val rows = composeRule.onAllNodesWithText(message).fetchSemanticsNodes()
            .filterNot { it.config.contains(SemanticsProperties.EditableText) }
        assertEquals("exactly one user message with the sent text must be visible", 1, rows.size)
    }

    private fun uncertainPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_RUN_UNCERTAIN).fetchSemanticsNodes().isNotEmpty()

    private fun stopControlPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isNotEmpty()

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
        const val PROFILE_NAME = "OR-06 Live Bridge"
        val NEW_SESSION_FAB: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_fab) }
        val NEW_SESSION_TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_title) }
        val NEW_SESSION_CREATE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_create) }
        val COMPOSER_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_input_label) }
        val SEND_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_send) }

        // Production tags (ui/chat/ApprovalDialog.kt, ui/chat/ChatScreen.kt).
        const val TAG_DENY = "approval_deny"
        const val TAG_ALLOW = "approval_allow"
        const val TAG_RUN_STOP = "composer_run_stop"
        const val TAG_RUN_UNCERTAIN = "chat_run_uncertain"

        const val UI_TIMEOUT_MS = 30_000L
        const val RUN_TIMEOUT_MS = 120_000L
    }
}
