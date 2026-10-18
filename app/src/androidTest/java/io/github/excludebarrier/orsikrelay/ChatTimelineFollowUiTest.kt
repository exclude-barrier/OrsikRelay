package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_TIMELINE
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic Compose tests for the timeline tail-follow behavior.
 *
 * The streaming seam is a fake: no network, no real hosts, no credentials.
 * Positions are read from the unclipped semantics bounds of the timeline and
 * of the streamed text: the LazyColumn semantics' `maxValue` is only an
 * estimate (`value + 100`) and cannot prove that the end of the content is
 * visible.
 */
class ChatTimelineFollowUiTest {
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
    fun openingALongSessionLandsAtTheEndOfTheTimeline() {
        val messages = (1L..80L).map { id ->
            HermesChatMessage(
                id = id,
                role = MessageRole.ASSISTANT,
                content = "Historie $id",
                timestampSeconds = null,
                toolName = null
            )
        }
        setScreen(history(messages))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Historie 80").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Historie 80").assertIsDisplayed()
        val last = composeRule.onNodeWithText("Historie 80").getUnclippedBoundsInRoot()
        val timeline = timelineBounds()
        assertTrue(
            "opening a session must land at its end (last row bottom ${last.bottom}, " +
                "viewport bottom ${timeline.bottom})",
            last.bottom <= timeline.bottom + 1.dp
        )
        assertTrue(
            "the last row must sit at the bottom edge",
            last.bottom >= timeline.bottom - TAIL_TOLERANCE
        )
    }

    @Test
    fun growingStreamedAnswerKeepsItsEndVisibleWhileTheUserIsAtTheBottom() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        startTurn(source)

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.AssistantDelta(lines(1, STREAM_LINES))
        )
        waitForStreamEndVisible()
        val firstBounds = assistantBounds()
        val firstHeight = firstBounds.bottom - firstBounds.top

        source.enqueue(ChatTurnEvent.AssistantDelta(lines(STREAM_LINES + 1, STREAM_LINES * 2)))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val bounds = assistantBoundsOrNull()
            bounds != null && (bounds.bottom - bounds.top) > firstHeight + 10.dp
        }
        waitForStreamEndVisible()
    }

    @Test
    fun growingStreamedAnswerDoesNotJumpAfterTheUserScrolledUp() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        startTurn(source)

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.AssistantDelta(lines(1, STREAM_LINES))
        )
        waitForStreamEndVisible()
        val beforeSwipe = assistantBounds()

        composeRule.onNodeWithTag(TAG_TIMELINE).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        val parked = assistantBounds()
        assertTrue(
            "the swipe must scroll the content down (top ${beforeSwipe.top} -> ${parked.top})",
            parked.top > beforeSwipe.top + 10.dp
        )
        assertTrue(
            "the stream end must now be below the viewport",
            parked.bottom > timelineBounds().bottom + 10.dp
        )

        val parkedHeight = parked.bottom - parked.top
        source.enqueue(ChatTurnEvent.AssistantDelta(lines(STREAM_LINES + 1, STREAM_LINES * 2)))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val bounds = assistantBoundsOrNull()
            bounds != null && (bounds.bottom - bounds.top) > parkedHeight + 10.dp
        }
        composeRule.waitForIdle()

        val afterGrowth = assistantBounds()
        assertEquals(
            "a growing answer must not yank a reader who scrolled up",
            parked.top.value,
            afterGrowth.top.value,
            2f
        )
        assertTrue(
            "the reader must stay away from the tail",
            afterGrowth.bottom > timelineBounds().bottom + 10.dp
        )
    }

    @Test
    fun growingStreamedAnswerDoesNotJumpAfterAnAccessibilityScrollUp() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        startTurn(source)

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.AssistantDelta(lines(1, STREAM_LINES))
        )
        waitForStreamEndVisible()
        val beforeSwipe = assistantBounds()

        // TalkBack and switch access scroll through the semantics action, not a
        // touch drag; that must end tail-follow just like a swipe.
        composeRule.onNodeWithTag(TAG_TIMELINE)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -600f) }
        composeRule.waitForIdle()
        val parked = assistantBounds()
        assertTrue(
            "the accessibility scroll must move the content down (top ${beforeSwipe.top} -> ${parked.top})",
            parked.top > beforeSwipe.top + 10.dp
        )
        assertTrue(
            "the stream end must now be below the viewport",
            parked.bottom > timelineBounds().bottom + 10.dp
        )

        val parkedHeight = parked.bottom - parked.top
        source.enqueue(ChatTurnEvent.AssistantDelta(lines(STREAM_LINES + 1, STREAM_LINES * 2)))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val bounds = assistantBoundsOrNull()
            bounds != null && (bounds.bottom - bounds.top) > parkedHeight + 10.dp
        }
        composeRule.waitForIdle()

        val afterGrowth = assistantBounds()
        assertEquals(
            "a growing answer must not yank a reader who scrolled up",
            parked.top.value,
            afterGrowth.top.value,
            2f
        )
        assertTrue(
            "the reader must stay away from the tail",
            afterGrowth.bottom > timelineBounds().bottom + 10.dp
        )
    }

    private fun startTurn(source: FakeTurnSource) {
        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Streaming")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue("the stream was not started", source.opened.await(3, TimeUnit.SECONDS))
        waitForText("Streaming")
    }

    private fun waitForStreamEndVisible() {
        var lastText: DpRect? = null
        var lastTimeline: DpRect? = null
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                lastText = assistantBoundsOrNull()
                lastTimeline = timelineBoundsOrNull()
                val text = lastText ?: return@waitUntil false
                val timeline = lastTimeline ?: return@waitUntil false
                text.bottom <= timeline.bottom + 1.dp &&
                    text.bottom >= timeline.bottom - TAIL_TOLERANCE
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("text=$lastText timeline=$lastTimeline", timeout)
        }
        composeRule.waitForIdle()
    }

    /** Unclipped bounds of the streaming assistant text (all lines, one node). */
    private fun assistantBounds(): DpRect =
        assistantBoundsOrNull() ?: error("the streamed assistant text is not composed")

    private fun assistantBoundsOrNull(): DpRect? = runCatching {
        composeRule
            .onNodeWithText(STREAM_LINE_PREFIX, substring = true)
            .getUnclippedBoundsInRoot()
    }.getOrNull()

    private fun timelineBounds(): DpRect =
        timelineBoundsOrNull() ?: error("the timeline is not composed")

    private fun timelineBoundsOrNull(): DpRect? = runCatching {
        composeRule.onNodeWithTag(TAG_TIMELINE).getUnclippedBoundsInRoot()
    }.getOrNull()

    private fun lines(from: Int, to: Int): String =
        (from..to).joinToString("\n") { "Zeile $it" }

    private fun setScreen(
        source: MessageHistorySource,
        chatSource: StreamingChatSource = StreamingChatSource { _, _, _ ->
            kotlinx.coroutines.flow.emptyFlow()
        },
        onBack: () -> Unit = {},
        runStopRequester: RunStopRequester = neverReachedStopRequester,
        runSteerRequester: RunSteerRequester = neverReachedSteerRequester,
        chatDraftRepository: ChatDraftRepository = NoOpChatDraftRepository(),
        runStatusRequester: RunStatusRequester = NoOpRunStatusRequester
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    runStopRequester = runStopRequester,
                    runSteerRequester = runSteerRequester,
                    chatDraftRepository = chatDraftRepository,
                    runStatusRequester = runStatusRequester,
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
                    runController = controllerFor(chatSource),
                    onBack = onBack
                )
            }
        }
    }

    private fun history(messages: List<HermesChatMessage>) =
        MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(messages))
        }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val profileId = AtomicReference<String>()
        val sessionId = AtomicReference<String>()
        val message = AtomicReference<String>()
        val opened = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        @Volatile
        private var activeChannel: Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            this.profileId.set(profileId)
            this.sessionId.set(sessionId)
            this.message.set(message)
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            activeChannel = channel
            opened.countDown()
            return flow {
                for (event in channel) {
                    emit(event)
                }
            }.onCompletion { cause ->
                if (cause is CancellationException) {
                    canceled.countDown()
                } else {
                    completed.set(true)
                }
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            activeChannel?.let { channel ->
                events.forEach { channel.trySend(it) }
            }
        }
    }

    private companion object {
        const val STREAM_LINES = 400
        const val STREAM_LINE_PREFIX = "Zeile"
        val TAIL_TOLERANCE = 96.dp

        val neverReachedStopRequester = object : RunStopRequester {
            override suspend fun stopRun(profileId: String, runId: String): Nothing {
                throw AssertionError("run-stop must not be reached in timeline tests")
            }
        }
        val neverReachedSteerRequester = object : RunSteerRequester {
            override suspend fun steerRun(
                profileId: String,
                runId: String,
                text: String
            ): Nothing {
                throw AssertionError("run-steer must not be reached in timeline tests")
            }
        }
    }
}
