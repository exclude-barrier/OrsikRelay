package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentKind
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_BAR
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Vertical placement of the composer in the normal (non-compact) chat layout.
 *
 * The composer is the fixed bottom element of the chat column: with an empty
 * session, a staged attachment or a turn-failure block the middle region is
 * still the only flexible part and fills the space between header and
 * composer. The composer must therefore always end exactly at the bottom edge
 * of the chat content area instead of floating up directly below the header.
 */
class ChatComposerBottomUiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun clearStagedAttachment() {
        ChatAttachmentDraftHolder.clear(PROFILE_ID, SESSION_ID)
    }

    @Test
    fun emptySessionKeepsTheComposerOnTheBottomEdge() {
        setScreen(MessageHistorySource { _, _ -> emptyHistory() })
        waitForText(context.getString(R.string.chat_empty))

        assertComposerEndsAtContentBottom()

        // The empty state stays at the top of the middle region, not squeezed
        // against the composer.
        val emptyBounds = composeRule.onNodeWithText(context.getString(R.string.chat_empty))
            .fetchSemanticsNode().boundsInRoot
        val composerTop = composeRule.onNodeWithTag(TAG_COMPOSER_BAR)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the empty-state text must stay above the composer, was bottom ${emptyBounds.bottom} vs composer top $composerTop",
            emptyBounds.bottom < composerTop
        )
    }

    @Test
    fun stagedAttachmentKeepsTheComposerOnTheBottomEdge() {
        stageAttachment()
        setScreen(MessageHistorySource { _, _ -> emptyHistory() })
        waitForText(ATTACHMENT_NAME)

        assertComposerEndsAtContentBottom()
    }

    @Test
    fun turnFailureBlockKeepsTheComposerOnTheBottomEdge() {
        val source = FakeTurnSource()
        setScreen(MessageHistorySource { _, _ -> emptyHistory() }, source)
        waitForText(context.getString(R.string.chat_empty))

        composeRule.onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Fehlerfall")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_send)).performClick()
        assertTrue("the turn must reach the streaming source", source.opened.await(3, TimeUnit.SECONDS))

        // A server-authoritative terminal failure with a confirmed run leaves
        // the visible turn-failure block in the middle region and frees the
        // composer again.
        source.enqueue(
            ChatTurnEvent.RunStarted(RUN_ID),
            ChatTurnEvent.TurnFailed(ChatStreamError.ServerError)
        )
        waitForText(context.getString(R.string.error_connection_http))
        // Typing focused the field and may have opened the real keyboard on
        // older devices: settle it before measuring the normal layout (the
        // composer sits above the keyboard, which would otherwise shorten the
        // content area by the IME inset).
        ImeClickSettle.settleAfterTyping(composeRule)

        assertComposerEndsAtContentBottom()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setScreen(
        source: MessageHistorySource,
        chatSource: StreamingChatSource = StreamingChatSource { _, _, _ ->
            kotlinx.coroutines.flow.emptyFlow()
        }
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    runController = RunSessionController(
                        chatSource = chatSource,
                        serviceStarter = noOpServiceStarter,
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = runStopRequesterThatIsNeverReached,
                    runSteerRequester = runSteerRequesterThatIsNeverReached,
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = PROFILE_ID,
                    sessionId = SESSION_ID,
                    onBack = {}
                )
            }
        }
    }

    /**
     * The composer is the last element of the chat column: its bottom edge
     * coincides with the bottom of the chat content area (the Compose root in
     * this host, which applies no IME/navigation-bar padding).
     */
    private fun assertComposerEndsAtContentBottom() {
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val composerBounds = composeRule.onNodeWithTag(TAG_COMPOSER_BAR)
            .fetchSemanticsNode().boundsInRoot
        val distance = rootBounds.bottom - composerBounds.bottom
        assertTrue(
            "the composer bottom ${composerBounds.bottom} must end at the content bottom ${rootBounds.bottom} (distance $distance px)",
            distance >= -1f && distance <= 1f
        )
    }

    private fun emptyHistory() = MessageHistoryResult.Success(MessagePage(emptyList()))

    private fun stageAttachment() {
        ChatAttachmentDraftHolder.put(
            PROFILE_ID,
            SESSION_ID,
            ChatAttachmentDraft(
                localId = "local-1",
                contentUri = "content://media/external/images/media/42",
                displayName = ATTACHMENT_NAME,
                mimeType = "image/png",
                sizeBytes = 1_500_000L,
                kind = ChatAttachmentKind.IMAGE
            )
        )
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /** Latched streaming fake: events are enqueued and closed by the test. */
    private class FakeTurnSource : StreamingChatSource {
        val opened = CountDownLatch(1)
        @Volatile
        private var activeChannel: Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            activeChannel = channel
            opened.countDown()
            return flow {
                for (event in channel) emit(event)
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            activeChannel?.let { channel -> events.forEach { channel.trySend(it) } }
        }
    }

    private val noOpServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val runStopRequesterThatIsNeverReached = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in layout tests")
        }
    }

    private val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing {
            throw AssertionError("run-steer must not be reachable in layout tests")
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val ATTACHMENT_NAME = "holiday.png"
        const val RUN_ID = "run_fixture_0001"
    }
}
