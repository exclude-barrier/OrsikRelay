package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.os.SystemClock
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
import io.github.excludebarrier.orsikrelay.data.run.SharedPreferencesRunLeaseStore
import io.github.excludebarrier.orsikrelay.di.AppDependencies
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A REAL process death while a server run is in flight.
 *
 * Two separate instrumentation invocations (`adb shell am instrument`, the app
 * stays installed in between, see docs/bridge-e2e.md §11):
 *
 *  1. [startLongRunThenDie]: pair, create a titled session through the real
 *     UI, send a long-running turn and wait until the controller has bound the
 *     server-confirmed run id AND the lease is persisted. The test then ends
 *     WITHOUT any cleanup; finishing the instrumentation kills the app process
 *     (a genuine process death, not a recreation).
 *  2. [recoverAfterProcessDeath]: a cold process. The persisted lease must
 *     still name the run; the real chat of that session must show the run as
 *     UNCERTAIN (never re-sent), the explicit reconciliation must settle it
 *     once the server run is terminal, the lease must be cleared and the
 *     message must exist exactly once.
 *
 * Arguments: `or09Phase` (start|recover) and `or09Title` (same unique session
 * title in both phases); phase 1 additionally `liveBaseUrl`/`livePairingCode`.
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeProcessDeathE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dependencies: AppDependencies get() = AppDependenciesHolder.get(context)

    @Test
    fun startLongRunThenDie() {
        assumeTrue("phase start not requested", arg("or09Phase") == "start")
        val baseUrl = arg("liveBaseUrl") ?: error("liveBaseUrl missing")
        val pairingCode = arg("livePairingCode") ?: error("livePairingCode missing")
        val title = arg("or09Title") ?: error("or09Title missing")

        dependencies.profileAdmin.listProfiles().forEach { dependencies.profileAdmin.deleteProfile(it.id) }
        dependencies.selectionStore.clear()
        val outcome = dependencies.pairingCoordinator.connect(PROFILE_NAME, baseUrl, pairingCode)
        assertTrue("expected DevicePairingOutcome.Success, got $outcome", outcome is DevicePairingOutcome.Success)
        val success = outcome as DevicePairingOutcome.Success
        dependencies.profileAdmin.selectActiveServer(success.profileId)
        dependencies.capabilityStore.write(success.profileId, success.sessionChatInteractiveApprovals)
        dependencies.notificationPermissionStore.markRequested()

        composeRule.activityRule.scenario.recreate()
        waitForText(context.getString(R.string.sessions_title))
        composeRule.onNodeWithContentDescription(context.getString(R.string.new_session_fab)).performClick()
        waitForText(context.getString(R.string.new_session_title))
        composeRule.onNodeWithText(context.getString(R.string.new_session_title_label)).performTextInput(title)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.new_session_create)).performClick()
        waitForComposerField()

        composeRule.onNodeWithText(COMPOSER_LABEL).performTextInput(message(title))
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(SEND_LABEL).assertIsEnabled()
        composeRule.onNodeWithText(SEND_LABEL).performClick()

        // The server-confirmed run id is bound and durably leased.
        var runId: String? = null
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            runId = dependencies.runController.activeRun.value?.runId?.takeIf { it.isNotBlank() }
            runId != null
        }
        val leaseStore = SharedPreferencesRunLeaseStore(context)
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { leaseStore.load()?.runId == runId }
        // Deliberately no cleanup: the instrumentation finishing kills the process.
    }

    @Test
    fun recoverAfterProcessDeath() {
        assumeTrue("phase recover not requested", arg("or09Phase") == "recover")
        val title = arg("or09Title") ?: error("or09Title missing")
        val leaseStore = SharedPreferencesRunLeaseStore(context)
        val lease = leaseStore.load()
        assertNotNull("the run lease must survive the process death", lease)
        lease!!

        try {
            waitForText(context.getString(R.string.sessions_title))
            waitForText(title)
            composeRule.onNodeWithText(title).performClick()

            // The restored run is UNCERTAIN: never re-sent, only reconciled.
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { tagPresent(TAG_RUN_UNCERTAIN) }
            val deadline = SystemClock.uptimeMillis() + RUN_TIMEOUT_MS
            while (tagPresent(TAG_RUN_UNCERTAIN) && SystemClock.uptimeMillis() < deadline) {
                if (tagPresent(TAG_RUN_RECONCILE)) {
                    composeRule.onNodeWithTag(TAG_RUN_RECONCILE).performClick()
                }
                val pollUntil = SystemClock.uptimeMillis() + RECONCILE_POLL_MS
                composeRule.waitUntil(timeoutMillis = RECONCILE_POLL_MS + 5_000L) {
                    SystemClock.uptimeMillis() >= pollUntil
                }
            }
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                !tagPresent(TAG_RUN_UNCERTAIN) && composerFieldEnabled()
            }
            composeRule.waitForIdle()

            // Authoritatively terminal on the server, lease released.
            val status = runBlocking { dependencies.runStatusRequester.getRunStatus(lease.profileId, lease.runId) }
            assertTrue("the leased run must be terminal, was $status",
                status is RunStatusResult.Known && status.status.isTerminal)
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { leaseStore.load() == null }
            assertNull("the lease must be cleared after reconciliation", leaseStore.load())

            // Exactly one user message: no blind resubmission after the death.
            val sent = message(title)
            composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
                .performScrollToNode(hasText(sent) and !hasSetTextAction())
            composeRule.waitForIdle()
            val rows = composeRule.onAllNodesWithText(sent).fetchSemanticsNodes()
                .filterNot { it.config.contains(SemanticsProperties.EditableText) }
            assertEquals("exactly one user message after recovery", 1, rows.size)
        } finally {
            dependencies.profileAdmin.listProfiles().forEach { dependencies.profileAdmin.deleteProfile(it.id) }
            AppDependenciesHolder.resetForTest()
        }
    }

    private fun message(title: String) =
        "$title - Führe bitte im Terminal-Tool genau `sleep 45 && echo fertig` aus " +
            "und antworte danach nur mit: fertig"

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
        const val PROFILE_NAME = "OR-09 Live Bridge"
        const val COMPOSER_LABEL = "Nachricht"
        const val SEND_LABEL = "Senden"
        const val TAG_RUN_UNCERTAIN = "chat_run_uncertain"
        const val TAG_RUN_RECONCILE = "chat_run_reconcile"
        const val UI_TIMEOUT_MS = 30_000L
        const val RUN_TIMEOUT_MS = 120_000L
        const val RECONCILE_POLL_MS = 3_000L
    }
}
