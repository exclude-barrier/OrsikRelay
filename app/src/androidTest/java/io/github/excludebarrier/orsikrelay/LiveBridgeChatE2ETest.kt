package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.di.AppDependencies
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provider-less part of the live chat UI end-to-end flow.
 *
 * This test drives the REAL [MainActivity], the real Compose screens and the
 * production dependency graph ([AppDependenciesHolder], reset around every
 * test) against a live Orsik Mobile Bridge. Unlike
 * [LiveBridgeE2ETest] (which only exercises the networking clients), it walks
 * the whole UI path: pair, create a session, send one message, reach the
 * terminal run state, survive a real Activity recreation.
 *
 * The live core behind the bridge has NO model provider, so a run ends
 * immediately with an assistant answer ("not connected to any AI provider").
 * The test therefore never inspects any assistant text - only that an
 * assistant row exists and the composer is send-ready again.
 *
 * The precondition is established WITHOUT driving the onboarding UI: the
 * profile is paired through the production `DevicePairingCoordinator` of the
 * graph and the active selection is persisted exactly like a successful
 * onboarding does. The `orsik://pair` deep link is deliberately NOT used - it
 * only PRE-fills the onboarding form, so it still needs the onboarding UI.
 *
 * Requires instrumentation arguments:
 *   liveBaseUrl      e.g. https://<bridge-host>.<tailnet>.ts.net:8443
 *   livePairingCode  a fresh 24-character single-use code
 *
 * Run (explicitly, against the live stack):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeChatE2ETest \
 *     -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=... \
 *     -Pandroid.testInstrumentationRunnerArguments.livePairingCode=...
 *
 * Stop/steer and the disconnect-with-identical-`run_id` path need a LONG
 * running turn and therefore a model provider; they are deliberately
 * NOT part of this provider-less flow.
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeChatE2ETest {

    // Real entry point: its ActivityScenarioRule launches MainActivity, and
    // `scenario.recreate()` performs a genuine Android recreation.
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var dependencies: AppDependencies
    private var pairedProfileId: String? = null

    /**
     * Unique per run, so "exactly one user message with the sent text" can
     * never collide with a message of a previous run or a parallel session.
     */
    // The instruction keeps the live answer short, so the transcript stays small.
    private val sentMessage = "OR-08a Ping ${UUID.randomUUID()} - antworte nur mit: ok"

    @Before
    fun pairProfileAndResetState() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)

        // Drop a process-scoped graph left over by a previous test (including
        // its live run controller), then pair through the PRODUCTION path: the
        // same DevicePairingCoordinator the onboarding route uses. The
        // onboarding UI is never driven.
        AppDependenciesHolder.resetForTest()
        dependencies = AppDependenciesHolder.get(context)

        // All stored profiles are removed first, so the only profile this test
        // can resolve is the one it pairs.
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

        // A live run posts a local notification, which would raise the
        // contextual POST_NOTIFICATIONS justification dialog over the chat
        // mid-test. The "ask at most once" flag is marked up front; the
        // permission flow itself is not part of this test.
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
    fun oneSentMessageStaysExactlyOnceAcrossARealActivityRecreation() {
        val profileId = pairedProfileId ?: error("the profile was not paired")

        // The rule launched MainActivity against the clean stores, so it is on
        // onboarding. One genuine recreation re-runs the production startup
        // resolution against the freshly paired, selected profile and lands on
        // the session list.
        composeRule.activityRule.scenario.recreate()
        waitForText(SESSIONS_TITLE)

        // 2. Create a new session through the real sessions UI (FAB + dialog).
        composeRule.onNodeWithContentDescription(NEW_SESSION_FAB).performClick()
        waitForText(NEW_SESSION_TITLE)
        composeRule.onNodeWithText(NEW_SESSION_CREATE).performClick()
        // The created session's chat opens; the composer only exists once the
        // history load reached its Ready state.
        waitForComposerField()

        // 3. Send one short message through the real composer (the labels come
        // from ChatScreen.kt: `chat_input_label` and `chat_send`).
        composeRule.onNodeWithText(COMPOSER_LABEL).performTextInput(sentMessage)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithContentDescription(SEND_LABEL).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SEND_LABEL).performClick()

        // 4. Bounded wait (no Thread.sleep) for the TERMINAL run state: an
        // assistant row exists AND the composer is ready again. The assistant
        // CONTENT is never asserted.
        waitForTerminalRun()
        composeRule.onNodeWithText(COMPOSER_LABEL).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SEND_LABEL).assertIsNotEnabled()
        assertTrue("an assistant message must be rendered", assistantRowPresent())

        // 5. Exactly one user message with the sent text - no duplicate POST.
        assertExactlyOneUserMessageOnScreen()

        // 6. Real Activity recreation: the same transcript comes back and
        // nothing is re-sent.
        composeRule.activityRule.scenario.recreate()
        waitForText(sentMessage)
        waitForComposerField()
        composeRule.waitForIdle()
        assertExactlyOneUserMessageOnScreen()
        assertTrue("an assistant message must be rendered", assistantRowPresent())
        // Send-ready with an empty draft: no automatic second send happened.
        composeRule.onNodeWithText(COMPOSER_LABEL).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SEND_LABEL).assertIsNotEnabled()

        // 7. The same transcript read through the production path.
        val sessionId = findSessionCarryingSentMessage(profileId)
        val history = dependencies.messageHistorySource.loadHistory(profileId, sessionId)
        val messages = (history as? MessageHistoryResult.Success)?.page?.messages
            ?: error("the transcript could not be read through the production path: $history")
        assertEquals(
            "exactly one user message with the sent text must be stored",
            1,
            messages.count { it.role == MessageRole.USER && it.content == sentMessage }
        )
    }

    /** Waits until the run reached its terminal state (assistant row + free composer). */
    private fun waitForTerminalRun() {
        composeRule.waitUntil(timeoutMillis = RUN_TIMEOUT_MS) {
            assistantRowPresent() && composerFieldEnabled()
        }
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

    /**
     * The assistant row is identified by its role label only
     * (`chat_role_assistant` = "Hermes"); the message text is never inspected.
     */
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
     * Counts the TRANSCRIPT rows carrying the sent text. The composer's own
     * value is an `EditableText` node and is never a transcript row, so it is
     * excluded explicitly - the assertion must not silently count a stale
     * draft as a second message.
     */
    private fun assertExactlyOneUserMessageOnScreen() {
        // A real (provider) answer can push the user row out of the lazy
        // transcript, where it is not composed and has no semantics node.
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(sentMessage) and !hasSetTextAction())
        composeRule.waitForIdle()
        val transcriptRows = composeRule.onAllNodesWithText(sentMessage)
            .fetchSemanticsNodes()
            .filterNot { it.config.contains(SemanticsProperties.EditableText) }
        assertEquals(
            "exactly one user message with the sent text must be visible",
            1,
            transcriptRows.size
        )
    }

    /**
     * Resolves the created session through the PRODUCTION read paths: the
     * session page source and the message history source of
     * [AppDependencies]. The sent text is unique per run, so exactly one
     * session may carry it - a second session (or a second message) would be
     * the duplicate the test is looking for.
     */
    private fun findSessionCarryingSentMessage(profileId: String): String {
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
                if (messages.any { it.role == MessageRole.USER && it.content == sentMessage }) {
                    matchingSessionIds += session.id
                }
            }
            if (!page.hasMore || page.sessions.isEmpty()) break
            offset += page.sessions.size
            pageIndex += 1
        }
        assertEquals(
            "exactly one session must carry the sent message",
            1,
            matchingSessionIds.size
        )
        return matchingSessionIds.single()
    }

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private companion object {
        const val PROFILE_NAME = "OR-08a Live Bridge"
        val SESSIONS_TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.sessions_title) }
        val NEW_SESSION_FAB: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_fab) }
        val NEW_SESSION_TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_title) }
        val NEW_SESSION_CREATE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_session_create) }
        val ASSISTANT_ROLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_role_assistant) }
        val COMPOSER_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_input_label) }
        val SEND_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_send) }

        /** Generous, bounded wait for UI transitions (network round-trips). */
        const val UI_TIMEOUT_MS = 30_000L

        /** Bounded wait for the run's terminal state on the provider-less core. */
        const val RUN_TIMEOUT_MS = 60_000L

        const val SESSION_PAGE_LIMIT = 50
        const val MAX_SESSION_PAGES = 5
    }
}
