package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
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
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live run-control part of the chat UI end-to-end flow (stop, steer,
 * disconnect/re-attach). It complements [LiveBridgeChatE2ETest], which
 * only covers the provider-less send path.
 *
 * This test drives the REAL [MainActivity], the real Compose screens and the
 * production dependency graph ([AppDependenciesHolder], reset around the test)
 * against a live Orsik Mobile Bridge whose core now has a REAL model provider
 * (DeepSeek). A long run is produced deterministically by asking the agent to
 * run the terminal command `sleep 40 && echo fertig` (no approval needed).
 * Assistant CONTENT is never asserted.
 *
 * Exactly ONE [Test] runs three phases sequentially in the SAME session
 * (a fresh single-use pairing code is only available once per test):
 *
 *  - A: send a long message, STOP the run, wait for the terminal state and
 *       verify the stopped run's server status through the production path
 *       (`GET /v1/runs/{run_id}` via [AppDependencies.runStatusRequester]).
 *  - B: send a long message, STEER the running turn, verify no failure state
 *       and that the turn still ends terminal.
 *  - C: send a long message, DISCONNECT the emulator network, re-enable it and
 *       verify the app re-attaches to the SAME run (same run id) and ends
 *       terminal with exactly one user message, or offers the honest
 *       uncertain/reconcile recovery. The code path is documented inline.
 *
 * Requires instrumentation arguments:
 *   liveBaseUrl      e.g. https://<bridge-host>.<tailnet>.ts.net:8443
 *   livePairingCode  a fresh 24-character single-use code
 *
 * Run (explicitly, against the live stack):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeRunControlE2ETest \
 *     -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=... \
 *     -Pandroid.testInstrumentationRunnerArguments.livePairingCode=...
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeRunControlE2ETest {

    // Real entry point: its ActivityScenarioRule launches MainActivity.
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var dependencies: AppDependencies
    private var pairedProfileId: String? = null

    /** Unique per run, so the stored transcript can never collide across runs. */
    private val stopMessage = longRunMessage("OR-08b Stop")
    private val steerMessage = longRunMessage("OR-08b Steer")
    private val disconnectMessage = longRunMessage("OR-08b Disconnect")
    private val steerText = "OR-08b Steer ${UUID.randomUUID()} - halte den Kurs"

    @Before
    fun pairProfileAndResetState() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)

        // Guarantee a usable radio even if a previous run died mid-disconnect.
        setNetworkEnabled(true)

        // Drop a process-scoped graph left over by a previous test (including
        // its live run controller), then pair through the PRODUCTION path: the
        // same DevicePairingCoordinator the onboarding route uses.
        AppDependenciesHolder.resetForTest()
        dependencies = AppDependenciesHolder.get(context)

        dependencies.profileAdmin.listProfiles().forEach {
            dependencies.profileAdmin.deleteProfile(it.id)
        }
        dependencies.selectionStore.clear()

        val outcome = dependencies.pairingCoordinator.connect(PROFILE_NAME, baseUrl!!, pairingCode!!)
        assertTrue(
            "expected DevicePairingOutcome.Success, got $outcome",
            outcome is DevicePairingOutcome.Success
        )
        val success = outcome as DevicePairingOutcome.Success
        pairedProfileId = success.profileId

        // Exactly what a successful onboarding persists: the fresh profile as
        // the active server plus its verified (non-secret) approval capability.
        dependencies.profileAdmin.selectActiveServer(success.profileId)
        dependencies.capabilityStore.write(
            success.profileId,
            success.sessionChatInteractiveApprovals
        )
        dependencies.notificationPermissionStore.markRequested()
    }

    @After
    fun reEnableNetworkAndResetState() {
        // The disconnect phase must never leave the emulator offline, even on
        // failure; this is the guaranteed restore point.
        setNetworkEnabled(true)
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
    fun stopSteerAndReconnectKeepOneUserMessagePerPhase() {
        val profileId = pairedProfileId ?: error("the profile was not paired")

        // Enter the session list through a real recreation (the reward of the
        // fresh pairing), then create and open a session via the real UI.
        composeRule.activityRule.scenario.recreate()
        waitForText(SESSIONS_TITLE)
        composeRule.onNodeWithContentDescription(NEW_SESSION_FAB).performClick()
        waitForText(NEW_SESSION_TITLE)
        composeRule.onNodeWithText(NEW_SESSION_CREATE).performClick()
        waitForComposerField()

        phaseAStop(profileId)
        phaseBSteer()
        phaseCDisconnectReAttach(profileId)

        // The strongest "no duplicate POST" proof: the production transcript
        // read of the session carries each phase's text exactly once.
        assertStoredExactlyOncePerPhase(profileId)
    }

    /**
     * Phase A - STOP.
     *
     * A stop is a write; after it the run must settle terminal. The production
     * graph exposes the confirmed run id ([AppDependencies.runController] ->
     * [io.github.excludebarrier.orsikrelay.domain.run.RunSessionController.activeRun]) and the
     * status read ([AppDependencies.runStatusRequester]); both are used to
     * verify the stop against the server, not only the UI.
     */
    private fun phaseAStop(profileId: String) {
        typeAndSend(stopMessage)
        waitForStopControl()
        val runIdBeforeStop = awaitRunIdForActiveRun()

        composeRule.onNodeWithTag(TAG_RUN_STOP).performClick()
        waitForTerminalRun()
        composeRule.onNodeWithText(COMPOSER_LABEL).assertIsEnabled()
        assertFalse("the stop control must be gone after the run ended", stopControlPresent())
        assertExactlyOneUserMessageOnScreen(stopMessage)
        assertTrue("an assistant message must be rendered", assistantRowPresent())

        // The server status read only exists for a confirmed run id; a stopped
        // run must settle TERMINAL and must NOT read as "completed" (the
        // core ends a stopped run with the terminal status `cancelled`,
        // docs/bridge-e2e.md).
        val status = awaitTerminalRunStatus(profileId, runIdBeforeStop)
        assertTrue("the stopped run must be terminal, was $status", status.isTerminal)
        assertNotEquals(
            "a stopped run must not report completed, was $status",
            RunServerStatus.COMPLETED,
            status
        )
    }

    /**
     * Phase B - STEER.
     *
     * A steer POST is only possible while a confirmed, non-blank run id is
     * bound (`ChatUiState.controlsVisible`). The acknowledgement is the queued
     * steer state; a refused steer (409) becomes a recoverable item offered as
     * a new message. A failed steer state would be a genuine finding.
     */
    private fun phaseBSteer() {
        typeAndSend(steerMessage)
        waitForStopControl()

        composeRule.onNodeWithTag(TAG_STEER_INPUT).performTextInput(steerText)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag(TAG_STEER_SEND).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_STEER_SEND).performClick()

        // Wait for the steer request to settle: queued acknowledgement, a
        // recoverable item (409) or the end of the run. A failure would also
        // satisfy this wait and is asserted against right after.
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            steerQueuedStatusPresent() || steerFailedStatusPresent() ||
                steerRecoveryPresent() || !stopControlPresent()
        }
        assertFalse(
            "a steer must not fail while the run is accepting input",
            steerFailedStatusPresent()
        )

        waitForTerminalRun()
        composeRule.waitForIdle()
        assertExactlyOneUserMessageOnScreen(steerMessage)
        assertTrue("an assistant message must be rendered", assistantRowPresent())
    }

    /**
     * Phase C - DISCONNECT / RE-ATTACH.
     *
     * The emulator network is disabled and re-enabled. The production code
     * plans two outcomes: the SSE stream is re-attached to the SAME server run
     * (`StreamingChatRepository` re-attach with `after_seq`, same run id,
     * no second POST) or, if the bounded re-attach ladder is exhausted, the run
     * is honestly marked UNCERTAIN with a reconcile action. Both are
     * accepted here; the same original run id is verified terminal through the
     * production status path either way.
     */
    private fun phaseCDisconnectReAttach(profileId: String) {
        typeAndSend(disconnectMessage)
        waitForStopControl()
        val runId = awaitRunIdForActiveRun()

        setNetworkEnabled(false)
        try {
            // Best-effort, bounded wait for a VISIBLE transport reaction (the
            // app may not even notice a short outage); the timeout is swallowed
            // because "no visible reaction" is a valid outcome.
            try {
                composeRule.waitUntil(timeoutMillis = OFFLINE_REACTION_TIMEOUT_MS) {
                    reconnectingStatusPresent() || reconcileButtonPresent() || !stopControlPresent()
                }
            } catch (_: ComposeTimeoutException) {
                // No reaction within the bound: fall through to the restore.
            }
            // Keep the outage bounded and short (no unbounded sleep chain).
            waitBoundedMillis(OFFLINE_MIN_HOLD_MS)
        } finally {
            setNetworkEnabled(true)
        }

        // Either the transport survived/re-attached and the run ends terminal,
        // or the app reports the unknown outcome with a reconcile action.
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            terminalRunReached() || reconcileButtonPresent()
        }
        if (reconcileButtonPresent()) {
            // Reconcile the unknown outcome against the server until the
            // run is proven terminal and its ownership released. The server
            // run is short, so a bounded retry loop is enough.
            val deadline = SystemClock.uptimeMillis() + RUN_TIMEOUT_MS
            while (reconcileButtonPresent() && SystemClock.uptimeMillis() < deadline) {
                composeRule.onNodeWithTag(TAG_RUN_RECONCILE).performClick()
                waitBoundedMillis(RECONCILE_POLL_MS)
            }
        }
        waitForTerminalRun()
        composeRule.waitForIdle()

        // The ORIGINAL run must be terminal server-side. A re-attach follows
        // THIS run (same id read through the production path); an accidental
        // second POST would have created a different run id.
        val status = awaitTerminalRunStatus(profileId, runId)
        assertTrue("the re-attached run must be terminal, was $status", status.isTerminal)

        assertExactlyOneUserMessageOnScreen(disconnectMessage)
        assertTrue("an assistant message must be rendered", assistantRowPresent())
    }

    // --- UI helpers (patterns shared with the live chat test) ---------------

    /** Types the message into the real composer and taps the real send action. */
    private fun typeAndSend(text: String) {
        composeRule.onNodeWithText(COMPOSER_LABEL).performTextInput(text)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(SEND_LABEL).assertIsEnabled()
        composeRule.onNodeWithText(SEND_LABEL).performClick()
    }

    /** The composer is send-ready again and no run control is visible. */
    private fun terminalRunReached(): Boolean =
        composerFieldEnabled() && !stopControlPresent()

    /** Waits for the terminal run state (composer ready, controls gone). */
    private fun waitForTerminalRun() {
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) { terminalRunReached() }
        composeRule.waitForIdle()
    }

    private fun waitForStopControl() {
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) { stopControlPresent() }
        composeRule.waitForIdle()
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

    private fun stopControlPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isNotEmpty()

    private fun reconcileButtonPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_RUN_RECONCILE).fetchSemanticsNodes().isNotEmpty()

    private fun reconnectingStatusPresent(): Boolean =
        composeRule.onAllNodesWithText(RECONNECTING_TEXT).fetchSemanticsNodes().isNotEmpty()

    private fun steerQueuedStatusPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_STEER_QUEUED_STATUS).fetchSemanticsNodes().isNotEmpty()

    private fun steerFailedStatusPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_STEER_FAILED_STATUS).fetchSemanticsNodes().isNotEmpty()

    private fun steerRecoveryPresent(): Boolean =
        composeRule.onAllNodesWithTag(TAG_STEER_RECOVERY).fetchSemanticsNodes().isNotEmpty()

    /** The assistant row is identified by its role label only; text is never checked. */
    private fun assistantRowPresent(): Boolean =
        composeRule.onAllNodesWithText(ASSISTANT_ROLE).fetchSemanticsNodes().isNotEmpty()

    /** True while the composer's input field is not disabled by an active turn. */
    private fun composerFieldEnabled(): Boolean =
        composeRule.onAllNodesWithText(COMPOSER_LABEL)
            .fetchSemanticsNodes()
            .any {
                it.config.contains(SemanticsProperties.EditableText) &&
                    !it.config.contains(SemanticsProperties.Disabled)
            }

    /**
     * Counts the TRANSCRIPT rows carrying [text]. The composer's own value is
     * an `EditableText` node and is never a transcript row, so it is excluded
     * explicitly.
     */
    private fun assertExactlyOneUserMessageOnScreen(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(text) and !hasSetTextAction())
        composeRule.waitForIdle()
        val transcriptRows = composeRule.onAllNodesWithText(text)
            .fetchSemanticsNodes()
            .filterNot { it.config.contains(SemanticsProperties.EditableText) }
        assertEquals(
            "exactly one user message with the sent text must be visible",
            1,
            transcriptRows.size
        )
    }

    // --- Production-path helpers --------------------------------------------

    /** Waits for the controller to bind the server-confirmed run id of the turn. */
    private fun awaitRunIdForActiveRun(): String {
        var runId: String? = null
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            runId = dependencies.runController.activeRun.value?.runId?.takeIf { it.isNotBlank() }
            runId != null
        }
        return runId ?: error("the production run controller did not bind a run id")
    }

    /**
     * Polls the production `GET /v1/runs/{run_id}` read (bounded) until the run
     * is terminal and returns the terminal status. Never retries beyond the
     * single read per poll; the poll interval is throttled so a live server is
     * not hammered.
     */
    private fun awaitTerminalRunStatus(profileId: String, runId: String): RunServerStatus {
        var observed: RunServerStatus? = null
        var lastPollAt = 0L
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPollAt >= STATUS_POLL_INTERVAL_MS) {
                lastPollAt = now
                val result = runBlocking {
                    dependencies.runStatusRequester.getRunStatus(profileId, runId)
                }
                if (result is RunStatusResult.Known) observed = result.status
            }
            observed?.isTerminal == true
        }
        return observed ?: error("the run status could not be read through the production path")
    }

    /**
     * Reads the session carrying [text] through the production read paths and
     * asserts each phase's text is stored as exactly ONE user message (no
     * duplicate POST anywhere in the run).
     */
    private fun assertStoredExactlyOncePerPhase(profileId: String) {
        val sessionId = findSessionCarrying(profileId, stopMessage)
        val history = dependencies.messageHistorySource.loadHistory(profileId, sessionId)
        val messages = (history as? MessageHistoryResult.Success)?.page?.messages
            ?: error("the transcript could not be read through the production path: $history")
        listOf(stopMessage, steerMessage, disconnectMessage).forEach { text ->
            assertEquals(
                "exactly one stored user message for phase text",
                1,
                messages.count { it.role == MessageRole.USER && it.content == text }
            )
        }
    }

    /**
     * Resolves the session carrying [text] through the production read paths
     * (session page source + message history source). The text is unique per
     * run, so exactly one session may carry it.
     */
    private fun findSessionCarrying(profileId: String, text: String): String {
        val matchingSessionIds = mutableListOf<String>()
        var offset = 0
        var pageIndex = 0
        while (pageIndex < MAX_SESSION_PAGES) {
            val result = dependencies.sessionPageSource.fetchPage(
                profileId = profileId,
                limit = SESSION_PAGE_LIMIT,
                offset = offset
            )
            val page = when (result) {
                is SessionListResult.Success -> result.page
                is SessionListResult.Failure -> error("the session list could not be read: $result")
            }
            page.sessions.forEach { session ->
                val history = dependencies.messageHistorySource.loadHistory(profileId, session.id)
                val messages =
                    (history as? MessageHistoryResult.Success)?.page?.messages ?: return@forEach
                if (messages.any { it.role == MessageRole.USER && it.content == text }) {
                    matchingSessionIds += session.id
                }
            }
            if (!page.hasMore || page.sessions.isEmpty()) break
            offset += page.sessions.size
            pageIndex += 1
        }
        assertEquals("exactly one session must carry the sent message", 1, matchingSessionIds.size)
        return matchingSessionIds.single()
    }

    // --- Emulator network control -------------------------------------------

    /**
     * Toggles the emulator radio through the instrumentation shell. Both wifi
     * and mobile data are addressed; a rejected command is ignored so the
     * caller always reaches its `finally`.
     */
    private fun setNetworkEnabled(enabled: Boolean) {
        val action = if (enabled) "enable" else "disable"
        runCatching { runShellCommand("svc wifi $action") }
        runCatching { runShellCommand("svc data $action") }
    }

    /** Runs one shell command, reading and closing its descriptor completely. */
    private fun runShellCommand(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    /** One bounded wait on the wall clock (no unbounded sleep chain). */
    private fun waitBoundedMillis(millis: Long) {
        val deadline = SystemClock.uptimeMillis() + millis
        composeRule.waitUntil(timeoutMillis = millis + WAIT_SLACK_MS) {
            SystemClock.uptimeMillis() >= deadline
        }
    }

    private fun longRunMessage(phase: String): String =
        "$phase ${UUID.randomUUID()} - Führe bitte im Terminal-Tool genau den Befehl " +
            "`sleep 40 && echo fertig` aus und antworte danach nur mit: fertig"

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private companion object {
        const val PROFILE_NAME = "OR-08b Live Bridge"
        const val SESSIONS_TITLE = "Sessions"
        const val NEW_SESSION_FAB = "Neue Session anlegen"
        const val NEW_SESSION_TITLE = "Neue Session"
        const val NEW_SESSION_CREATE = "Erstellen"
        const val ASSISTANT_ROLE = "Hermes"
        const val COMPOSER_LABEL = "Nachricht"
        const val SEND_LABEL = "Senden"
        const val RECONNECTING_TEXT = "Verbinde neu …"

        // Test tags from ui/chat/ChatScreen.kt.
        const val TAG_RUN_STOP = "composer_run_stop"
        const val TAG_RUN_RECONCILE = "chat_run_reconcile"
        const val TAG_STEER_INPUT = "chatSteerInput"
        const val TAG_STEER_SEND = "chatSteerSend"
        const val TAG_STEER_QUEUED_STATUS = "chatSteerQueuedStatus"
        const val TAG_STEER_FAILED_STATUS = "chatSteerFailedStatus"
        const val TAG_STEER_RECOVERY = "chatSteerRecovery:0"

        /** Generous, bounded wait for UI transitions (network round-trips). */
        const val UI_TIMEOUT_MS = 30_000L

        /** Bounded wait for one run phase (start, stop, steer, reconnect). */
        const val RUN_TIMEOUT_MS = 120_000L

        /** Best-effort wait for a visible reaction to the network outage. */
        const val OFFLINE_REACTION_TIMEOUT_MS = 10_000L

        /** Minimum bounded outage duration after the reaction wait. */
        const val OFFLINE_MIN_HOLD_MS = 5_000L

        /** Slack added to a bounded wall-clock wait so it cannot time out. */
        const val WAIT_SLACK_MS = 5_000L

        /** Throttle between two production run-status reads. */
        const val STATUS_POLL_INTERVAL_MS = 500L

        /** Wait between two uncertain-run reconcile attempts. */
        const val RECONCILE_POLL_MS = 2_000L

        const val SESSION_PAGE_LIMIT = 50
        const val MAX_SESSION_PAGES = 5
    }
}
