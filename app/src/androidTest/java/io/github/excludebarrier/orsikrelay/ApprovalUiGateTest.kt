package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
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
 * Compose tests for the run-approval GATE.
 *
 * A live, strictly validated pending approval always renders: the event is
 * stronger evidence than cached capability metadata. Without a pending
 * request the UI still renders nothing. Tests use synthetic fixtures only.
 */
class ApprovalUiGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validatedPendingRequestRendersWithoutASecondUiFlag() {
        val calls = AtomicInteger()
        setGate(
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
    fun nullRequestRendersNothing() {
        val calls = AtomicInteger()
        setGate(
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
    fun validatedRequestRendersDialog() {
        setGate(
            pendingApproval = REQUEST,
            requester = fake { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(HINT_LABEL).assertIsDisplayed()
    }

    @Test
    fun changingRequestIdResetsTerminalDialogState() {
        val calls = AtomicInteger()
        lateinit var replaceRequest: () -> Unit
        composeRule.setContent {
            var request by remember { mutableStateOf(REQUEST) }
            replaceRequest = { request = SECOND_REQUEST }
            HermesTheme {
                ApprovalUi(
                    pendingApproval = request,
                    profileId = PROFILE_ID,
                    requester = fake { _, runId, _, _ ->
                        calls.incrementAndGet()
                        RunApprovalResult.NoPending(runId)
                    },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitForIdle()
        assertEquals(1, calls.get())

        composeRule.runOnIdle { replaceRequest() }
        composeRule.onNodeWithTag(TAG_ALLOW).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun tappingAllowOnceForwardsTheRequestExactlyOnce() {
        val calls = AtomicInteger()
        setGate(
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

    @Test
    fun noPendingReportsTheFinishedRequestAndKeepsTheNote() {
        val calls = AtomicInteger()
        val finished = AtomicInteger()
        setGate(
            pendingApproval = REQUEST,
            onFinished = { finished.incrementAndGet() },
            requester = fake { _, runId, _, _ ->
                calls.incrementAndGet()
                RunApprovalResult.NoPending(runId)
            }
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitForIdle()

        // One request, one authoritative terminal answer: the app-level
        // record is reported finished exactly once, the dialog stays with
        // its note and both buttons are terminally disabled.
        assertEquals(1, calls.get())
        assertEquals(1, finished.get())
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(ALREADY_RESOLVED).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ALLOW).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_DENY).assertIsNotEnabled()
    }

    @Test
    fun ambiguousFailureNeitherReportsFinishedNorReenablesTheButtons() {
        val calls = AtomicInteger()
        val finished = AtomicInteger()
        setGate(
            pendingApproval = REQUEST,
            onFinished = { finished.incrementAndGet() },
            requester = fake { _, _, _, _ ->
                calls.incrementAndGet()
                RunApprovalResult.Failure(
                    io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError.Network
                )
            }
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitForIdle()

        // An ambiguous transport result must NOT drop the app-level record
        // (the request has to stay resolvable) and the buttons stay disabled
        // in this composition.
        assertEquals(1, calls.get())
        assertEquals(0, finished.get())
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ALLOW).assertIsNotEnabled()
    }

    private fun setGate(
        pendingApproval: ApprovalRequest?,
        requester: RunApprovalRequester,
        onFinished: () -> Unit = {}
    ) {
        composeRule.setContent {
            // A Resolved decision closes the dialog: mirror production by
            // removing the gate from the composition on onDismiss.
            var show by remember { mutableStateOf(true) }
            if (show) {
                HermesTheme {
                    ApprovalUi(
                        pendingApproval = pendingApproval,
                        profileId = PROFILE_ID,
                        requester = requester,
                        onDismiss = { show = false },
                        onFinished = onFinished
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
        const val RUN_ID = "run_0123456789abcdef0123456789abcdef"
        const val REQUEST_ID = "fedcba9876543210fedcba9876543210"

        val TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_title) }
        val HINT_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_reason_label) }

        const val TAG_ALLOW = "approval_allow"
        const val TAG_DENY = "approval_deny"
        val ALREADY_RESOLVED: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_already_resolved) }

        val REQUEST = ApprovalRequest(
            runId = RUN_ID,
            requestId = REQUEST_ID,
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
        val SECOND_REQUEST = ApprovalRequest(
            runId = RUN_ID,
            requestId = "00112233445566778899aabbccddeeff",
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
    }
}
