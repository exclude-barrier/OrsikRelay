package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.ui.chat.ApprovalDialog
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the run-approval decision dialog.
 *
 * All values are synthetic fixtures; the requester seam is a fake, so no
 * network, no real hosts, no credentials and no real Hermes approvals are
 * involved. The dialog renders only the protocol-defined neutral approval
 * prompt and explicit deny/allow-once controls; no dynamic action detail can
 * be represented by [ApprovalRequest].
 */
class ApprovalDialogUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOnlyNeutralApprovalPromptNotIdsOrDynamicActionDetails() {
        setDialog(fake(requester = { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }))

        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_VALUE).assertIsDisplayed()
        composeRule.onNodeWithText(REASON_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(SAFE_DESCRIPTION).assertIsDisplayed()

        listOf(RUN_ID, REQUEST_ID, COMMAND, TARGET_LABEL, RISK_NOTE).forEach { forbidden ->
            assertTrue(
                "$forbidden must never be shown",
                composeRule.onAllNodesWithText(forbidden).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun dialogDrawsAnOpaqueSurfaceBehindItsContent() {
        // BasicAlertDialog has no container of its own; without the surface
        // the prompt would be drawn transparently over the chat behind it.
        setDialog(fake(requester = { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }))

        val pixels = composeRule.onNodeWithTag(TAG_SURFACE).captureToImage().toPixelMap()
        // Top padding row (above the title) and the centre: background only.
        listOf(pixels.width / 2 to 4, pixels.width / 2 to pixels.height / 2).forEach { (x, y) ->
            assertEquals("surface pixel ($x,$y) must be opaque", 1f, pixels[x, y].alpha, 0.001f)
        }
    }

    @Test
    fun denyIsFirstButtonAndLeftOfAllow() {
        setDialog(fake(requester = { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }))

        val denyBounds = composeRule.onNodeWithTag(TAG_DENY).assertIsDisplayed().getBoundsInRoot()
        val allowBounds = composeRule.onNodeWithTag(TAG_ALLOW).assertIsDisplayed().getBoundsInRoot()

        // Both live in one horizontal Row; Deny (the filled, primary, safe
        // default) is the FIRST element, i.e. strictly left of Allow.
        assertTrue(
            "Deny must be left of Allow (first in the row)",
            denyBounds.right.value <= allowBounds.left.value
        )
    }

    @Test
    fun outsideTapDoesNotCloseTheDialog() {
        var dismissed = false
        setDialog(
            fake(requester = { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }),
            onDismiss = { dismissed = true }
        )

        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        // A tap in the top-left window corner (far outside the centered
        // dialog) must not dismiss: onDismissRequest is a no-op, so it can
        // neither approve, deny nor close the pending decision.
        //
        // Anchor the injection on the single title node (onRoot() resolves to
        // TWO roots under createComposeRule, and this ui-test version has no
        // onFirst/onLast); the coordinates below are absolute root coords, so
        // the tap still lands on the window corner — outside the dialog.
        composeRule.onNodeWithText(TITLE).performTouchInput {
            // Tap = down(Offset) then up(pointerId). No prior moveTo: a
            // gesture is only "in progress" after down.
            val corner = Offset(1f, 1f)
            down(corner)
            up(0)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        assertFalse(dismissed)
    }

    @Test
    fun doubleTapOnAllowStartsExactlyOneRespond() {
        val calls = AtomicInteger()
        // The first decision suspends INDEFINITELY (but non-blocking, on the
        // compose main thread) so it is still in flight when the second tap
        // lands. The in-flight lock is set synchronously in decide(), so the
        // second tap must be ignored and never open a second request.
        setDialog(
            fake(
                requester = { _, _, _, _ ->
                    calls.incrementAndGet()
                    suspendCancellableCoroutine<Unit> { /* park; never resumes */ }
                    RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
                }
            )
        )

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        // Let the first (now parked) request start so the lock is engaged.
        composeRule.waitForIdle()
        assertTrue("first tap must start exactly one request", calls.get() >= 1)

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitForIdle()

        // Exactly one request was ever started, despite two taps.
        assertEquals(1, calls.get())
    }

    @Test
    fun failureShowsGenericMessageAndPermanentlyDisablesBothChoices() {
        val calls = AtomicInteger()
        setDialog(
            fake(
                requester = { _, _, _, _ ->
                    calls.incrementAndGet()
                    RunApprovalResult.Failure(RunApprovalError.Network)
                }
            )
        )

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(ERROR_GENERIC).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(1, calls.get())

        // An ambiguous result is terminal: neither decision can be sent
        // again, because the first POST may already have been applied.
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(ERROR_GENERIC).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_DENY).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_ALLOW).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        composeRule.waitForIdle()
        assertEquals(1, calls.get())
    }

    @Test
    fun noPendingShowsNoteAndDisablesButtons() {
        var dismissed = false
        setDialog(
            fake(requester = { _, _, _, _ -> RunApprovalResult.NoPending(RUN_ID) }),
            onDismiss = { dismissed = true }
        )

        composeRule.onNodeWithTag(TAG_DENY).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(ALREADY_RESOLVED).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ALREADY_RESOLVED).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_DENY).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_ALLOW).assertIsNotEnabled()
        // NoPending is a terminal outcome, not a dismissal: the dialog is
        // not closed and onDismiss is not invoked.
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        assertFalse(dismissed)
    }

    @Test
    fun resolvedClosesViaOnDismissCallback() {
        var dismissed = false
        setDialog(
            fake(requester = { _, _, _, _ ->
                RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
            }),
            onDismiss = { dismissed = true }
        )

        composeRule.onNodeWithTag(TAG_ALLOW).performClick()
        waitForGone()
        assertTrue(dismissed)
    }

    @Test
    fun denyChoiceIsForwardedToTheRequesterWithExactArgs() {
        val seenProfile = mutableListOf<String>()
        val seenRun = mutableListOf<String>()
        val seenRequest = mutableListOf<String>()
        val seenChoice = mutableListOf<RunApprovalChoice>()
        setDialog(
            fake(
                requester = { profileId, runId, requestId, choice ->
                    seenProfile += profileId
                    seenRun += runId
                    seenRequest += requestId
                    seenChoice += choice
                    RunApprovalResult.Resolved(runId, choice)
                }
            )
        )

        composeRule.onNodeWithTag(TAG_DENY).performClick()
        waitForGone()

        assertEquals(listOf(PROFILE_ID), seenProfile)
        assertEquals(listOf(RUN_ID), seenRun)
        assertEquals(listOf(REQUEST_ID), seenRequest)
        assertEquals(listOf(RunApprovalChoice.DENY), seenChoice)
    }

    private fun setDialog(requester: RunApprovalRequester, onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                HermesTheme {
                    ApprovalDialog(
                        request = REQUEST,
                        profileId = PROFILE_ID,
                        requester = requester,
                        onDismiss = {
                            show = false
                            onDismiss()
                        }
                    )
                }
            }
        }
    }

    private fun fake(
        requester: suspend (String, String, String, RunApprovalChoice) -> RunApprovalResult
    ): RunApprovalRequester =
        object : RunApprovalRequester {
            override suspend fun respond(
                profileId: String,
                runId: String,
                requestId: String,
                choice: RunApprovalChoice
            ): RunApprovalResult = requester(profileId, runId, requestId, choice)
        }

    private fun waitForGone() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(TITLE).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val RUN_ID = "run_0123456789abcdef0123456789abcdef"
        const val REQUEST_ID = "fedcba9876543210fedcba9876543210"
        const val COMMAND = "synthetic-command"

        val TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_title) }
        val ACTION_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_action_label) }
        val ACTION_VALUE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_action_value) }
        const val TARGET_LABEL = "Ziel"
        val REASON_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_reason_label) }
        val SAFE_DESCRIPTION: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.approval_safe_description)
        }
        const val RISK_NOTE =
            "Sicherheitsmodul empfiehlt Ablehnung. Erlauben nur, wenn du die Aktion bewusst freigibst."
        val ERROR_GENERIC: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_error_generic) }
        val ALREADY_RESOLVED: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_already_resolved) }

        const val TAG_SURFACE = "approval_surface"
        const val TAG_DENY = "approval_deny"
        const val TAG_ALLOW = "approval_allow"

        val REQUEST = ApprovalRequest(
            runId = RUN_ID,
            requestId = REQUEST_ID,
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
    }
}
