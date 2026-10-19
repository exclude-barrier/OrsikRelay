package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noOpRunServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private fun controllerFor(source: StreamingChatSource) = RunSessionController(
        chatSource = source,
        serviceStarter = noOpRunServiceStarter,
        notificationCoordinator = NoOpNotificationCoordinator
    )

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingIsShownUntilHistoryReturns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        setScreen(MessageHistorySource { _, _ ->
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
            success(emptyList())
        })

        assertTrue(started.await(3, TimeUnit.SECONDS))
        composeRule.onNodeWithText(context.getString(R.string.chat_loading)).assertIsDisplayed()
        release.countDown()
        waitForText(context.getString(R.string.chat_empty))
    }

    @Test
    fun allRolesAreRenderedAndHistoryToolContentIsRedacted() {
        setScreen(MessageHistorySource { _, _ ->
            success(
                listOf(
                    message(1, MessageRole.USER, "Fixture-Frage"),
                    message(2, MessageRole.ASSISTANT, "Fixture-Antwort"),
                    message(3, MessageRole.SYSTEM, "Fixture-System"),
                    message(
                        4,
                        MessageRole.TOOL,
                        """{"output":"SECRET_HISTORY_TOOL_123","exit_code":0,"error":null}""",
                        "fixture_tool"
                    )
                )
            )
        })

        waitForText("Fixture-Antwort")
        // The user and assistant sender labels are no longer visible, but they
        // remain the per-message accessibility label.
        listOf(
            context.getString(R.string.chat_role_user) to true,
            context.getString(R.string.chat_role_assistant) to true,
            context.getString(R.string.chat_role_system) to false,
            context.getString(R.string.chat_role_tool_named, "fixture_tool") to false
        ).forEach { (label, isContentDescription) ->
            if (isContentDescription) {
                composeRule.onNodeWithContentDescription(label).assertExists()
            } else {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }
        }
        // Non-tool content is rendered verbatim…
        listOf("Fixture-Frage", "Fixture-Antwort", "Fixture-System").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        // …but the untrusted tool content never reaches the UI: only the
        // safe role label is shown (history tool redaction). Tool content
        // renders as one plain-text node when present, so exact text
        // queries must find no node for the raw payload or any part of it.
        composeRule.onAllNodesWithText("SECRET_HISTORY_TOOL_123").assertCountEquals(0)
        composeRule.onAllNodesWithText("""{"output":"SECRET_HISTORY_TOOL_123","exit_code":0,"error":null}""").assertCountEquals(0)
    }

    @Test
    fun unnamedHistoryToolShowsOnlyTheGenericSafeLabel() {
        setScreen(MessageHistorySource { _, _ ->
            success(
                listOf(
                    message(1, MessageRole.TOOL, "rm -rf /tmp/orsik_p3_e2e_dir", null)
                )
            )
        })

        waitForText(context.getString(R.string.chat_tool_activity_history))
        composeRule.onAllNodesWithText("rm -rf /tmp/orsik_p3_e2e_dir").assertCountEquals(0)
    }

    @Test
    fun historyToolPathAndUrlContentIsNotRendered() {
        setScreen(MessageHistorySource { _, _ ->
            success(
                listOf(
                    message(1, MessageRole.TOOL, "/home/user/.ssh/id_rsa", "terminal"),
                    message(2, MessageRole.TOOL, "https://example.com/a?token=abc", null)
                )
            )
        })

        waitForText("Tool: terminal")
        composeRule.onAllNodesWithText("/home/user/.ssh/id_rsa").assertCountEquals(0)
        composeRule.onAllNodesWithText("https://example.com/a?token=abc").assertCountEquals(0)
    }

    @Test
    fun failureIsGenericAndRetryIsManual() {
        val calls = AtomicInteger()
        setScreen(MessageHistorySource { _, _ ->
            if (calls.incrementAndGet() == 1) {
                MessageHistoryResult.Failure(MessageHistoryError.Network)
            } else {
                success(listOf(message(1, MessageRole.ASSISTANT, "Nach Retry")))
            }
        })

        waitForText(context.getString(R.string.error_chat_generic))
        assertEquals(1, calls.get())
        composeRule.onNodeWithText(context.getString(R.string.sessions_retry)).performClick()
        waitForText("Nach Retry")
        assertEquals(2, calls.get())
    }

    @Test
    fun historyFailureShowsHintAndKeepsRetryAtTheBottomWithOneLoadPerTap() {
        val calls = AtomicInteger()
        setScreen(MessageHistorySource { _, _ ->
            calls.incrementAndGet()
            MessageHistoryResult.Failure(MessageHistoryError.Network)
        })

        waitForText(context.getString(R.string.error_chat_generic))
        composeRule.onNodeWithText(context.getString(R.string.sessions_error_hint_unreachable))
            .assertIsDisplayed()

        // The retry action is full-width at the bottom of the content area,
        // not directly under the message: its center is in the lower half of
        // the screen.
        val retry = composeRule.onNodeWithText(context.getString(R.string.sessions_retry))
        val rootHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val retryCenterY = retry.fetchSemanticsNode().boundsInRoot.center.y
        assertTrue(
            "retry must sit in the lower half of the screen, center was $retryCenterY of $rootHeight",
            retryCenterY > rootHeight / 2f
        )

        retry.performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { calls.get() == 2 }
        composeRule.waitForIdle()
        assertEquals("exactly one history load per tap", 2, calls.get())

        retry.performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { calls.get() == 3 }
        composeRule.waitForIdle()
        assertEquals("exactly one history load per tap", 3, calls.get())
    }

    @Test
    fun backButtonInvokesBackAction() {
        val invoked = AtomicBoolean(false)
        setScreen(
            source = MessageHistorySource { _, _ -> success(emptyList()) },
            onBack = { invoked.set(true) }
        )

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_back)).performClick()
        assertTrue(invoked.get())
    }

    private fun setScreen(
        source: MessageHistorySource,
        runController: RunSessionController = controllerFor(streamingSourceThatNeverEmits),
        onBack: () -> Unit = {},
        runStopRequester: RunStopRequester = runStopRequesterThatIsNeverReached,
        runSteerRequester: RunSteerRequester = runSteerRequesterThatIsNeverReached,
        chatDraftRepository: ChatDraftRepository = NoOpChatDraftRepository()
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    runController = runController,
                    runStopRequester = runStopRequester,
                    runSteerRequester = runSteerRequester,
                    chatDraftRepository = chatDraftRepository,
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
                    onBack = onBack
                )
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun success(messages: List<HermesChatMessage>) =
        MessageHistoryResult.Success(MessagePage(messages))

    private fun message(
        id: Long,
        role: MessageRole,
        content: String,
        toolName: String? = null
    ) = HermesChatMessage(
        id = id,
        role = role,
        content = content,
        timestampSeconds = null,
        toolName = toolName
    )

    private companion object {
        // These tests exercise the history slice only; the streaming seam
        // is a synthetic fake that never emits and never fails, and the
        // run-stop seam is a synthetic fake that must never be reached
        // (no turn is ever started here).
        val streamingSourceThatNeverEmits = StreamingChatSource { _, _, _ ->
            kotlinx.coroutines.flow.emptyFlow()
        }
        // RunStopRequester is a plain interface (no fun interface), so the
        // never-reached fake is a small object implementation.
        val runStopRequesterThatIsNeverReached = object : RunStopRequester {
            override suspend fun stopRun(profileId: String, runId: String): Nothing {
                throw AssertionError("run-stop must not be reachable in history-slice tests")
            }
        }
        val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
            override suspend fun steerRun(
                profileId: String,
                runId: String,
                text: String
            ): Nothing {
                throw AssertionError("run-steer must not be reachable in history-slice tests")
            }
        }
    }
}
