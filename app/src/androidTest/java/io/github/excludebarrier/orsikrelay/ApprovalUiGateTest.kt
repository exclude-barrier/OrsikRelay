package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.ui.chat.ApprovalUi
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the run-approval GATE (Mission 014).
 *
 * A live, strictly validated pending approval always renders: the event is
 * stronger evidence than cached capability metadata. Without a pending
 * request the UI still renders nothing. Tests use synthetic fixtures only.
 */
class ApprovalUiGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capabilityFalseStillRendersAValidatedPendingRequest() {
        val calls = AtomicInteger()
        setGate(
            approvalUiEnabled = false,
            pendingApproval = REQUEST,
            requester = fake { _, _, _, _ ->
                calls.incrementAndGet()
                RunApprovalResult.NoPending(RUN_ID)
            }
        )

        composeRule.waitForIdle()
        assertTrue(
            "a valid pending request must not be hidden by stale capability metadata",
            composeRule.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun nullRequestRendersNothingEvenWithCapabilityTrue() {
        val calls = AtomicInteger()
        setGate(
            approvalUiEnabled = true,
            pendingApproval = null,
            requester = fake { _, _, _, _ ->
                calls.incrementAndGet()
                RunApprovalResult.NoPending(RUN_ID)
            }
        )

        composeRule.waitForIdle()
        assertTrue(
            "no dialog content may render without a pending request",
            composeRule.onAllNodesWithText(TITLE).fetchSemanticsNodes().isEmpty()
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun capabilityTrueAndRequestRendersDialog() {
        setGate(
            approvalUiEnabled = true,
            pendingApproval = REQUEST,
            requester = fake { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(TARGET_LABEL).assertIsDisplayed()
    }

    @Test
    fun tappingAllowOnceForwardsTheRequestExactlyOnce() {
        val calls = AtomicInteger()
        setGate(
            approvalUiEnabled = true,
            pendingApproval = REQUEST,
            requester = fake { _, _, _, _ ->
                calls.incrementAndGet()
                RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
            }
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(TITLE).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, calls.get())
    }

    private fun setGate(
        approvalUiEnabled: Boolean,
        pendingApproval: ApprovalRequest?,
        requester: RunApprovalRequester
    ) {
        composeRule.setContent {
            // A Resolved decision closes the dialog: mirror production by
            // removing the gate from the composition on onDismiss.
            var show by remember { mutableStateOf(true) }
            if (show) {
                HermesTheme {
                    ApprovalUi(
                        approvalUiEnabled = approvalUiEnabled,
                        pendingApproval = pendingApproval,
                        profileId = PROFILE_ID,
                        requester = requester,
                        onDismiss = { show = false }
                    )
                }
            }
        }
    }

    private fun fake(
        respond: (String, String, String, RunApprovalChoice) -> RunApprovalResult
    ): RunApprovalRequester =
        object : RunApprovalRequester {
            override suspend fun respond(
                profileId: String,
                runId: String,
                requestId: String,
                choice: RunApprovalChoice
            ): RunApprovalResult = respond(profileId, runId, requestId, choice)
        }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val RUN_ID = "run_synthetic"
        const val REQUEST_ID = "request_synthetic"

        const val TITLE = "Freigabe anfragen"
        const val TARGET_LABEL = "Ziel"

        const val TAG_ALLOW = "approval_allow"

        val REQUEST = ApprovalRequest(
            runId = RUN_ID,
            requestId = REQUEST_ID,
            command = "synthetic-command",
            description = "synthetic-reason",
            smartDenied = true
        )
    }
}
