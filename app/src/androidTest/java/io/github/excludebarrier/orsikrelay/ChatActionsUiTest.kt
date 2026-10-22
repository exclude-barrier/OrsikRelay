package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManager
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_ARCHIVE
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_DELETE
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_MORE
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSION_DELETE_CONFIRM_TAG
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Archive and delete from the chat header (alpha.13). */
class ChatActionsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeManager(private val supported: Boolean = true) : SessionManager {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun supportsManagement(profileId: String) = supported
        override fun setArchived(profileId: String, sessionId: String, archived: Boolean): SessionManageResult {
            calls += "archive:$sessionId:$archived"
            return SessionManageResult.Success
        }
        override fun delete(profileId: String, sessionId: String): SessionManageResult {
            calls += "delete:$sessionId"
            return SessionManageResult.Success
        }
    }

    private fun show(manager: SessionManager, backs: AtomicInteger = AtomicInteger()) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = MessageHistorySource { _, _ ->
                        MessageHistoryResult.Success(
                            MessagePage(listOf(HermesChatMessage(1, MessageRole.ASSISTANT, "Hallo aus dem Chat", null, null)))
                        )
                    },
                    runController = RunSessionController(
                        chatSource = StreamingChatSource { _, _, _ -> emptyFlow() },
                        serviceStarter = object : RunForegroundServiceStarter {
                            override fun start(profileId: String, sessionId: String) {}
                            override fun stop() {}
                        },
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = object : RunStopRequester {
                        override suspend fun stopRun(profileId: String, runId: String): Nothing = throw AssertionError("unused")
                    },
                    runSteerRequester = object : RunSteerRequester {
                        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing =
                            throw AssertionError("unused")
                    },
                    chatDraftRepository = NoOpChatDraftRepository(),
                    sessionManager = manager,
                    profileId = "profile",
                    sessionId = "chat-1",
                    sessionTitle = "Logs aufräumen",
                    onBack = { backs.incrementAndGet() }
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Hallo aus dem Chat").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withoutAdvertisementThereIsNoMenu() {
        show(FakeManager(supported = false))

        composeRule.onAllNodesWithTag(TAG_CHAT_MORE).assertCountEquals(0)
    }

    @Test
    fun archiveStaysInTheChatAndOffersUndo() {
        val manager = FakeManager()
        val backs = AtomicInteger()
        show(manager, backs)

        composeRule.onNodeWithTag(TAG_CHAT_MORE).performClick()
        composeRule.onNodeWithTag(TAG_CHAT_ARCHIVE).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.sessions_undo)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.sessions_archived)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_undo)).performClick()
        composeRule.waitUntil(5_000) { manager.calls.size == 2 }

        assertEquals(listOf("archive:chat-1:true", "archive:chat-1:false"), manager.calls.toList())
        assertEquals(0, backs.get())
    }

    @Test
    fun deleteAsksFirstThenLeavesTheChat() {
        val manager = FakeManager()
        val backs = AtomicInteger()
        show(manager, backs)

        composeRule.onNodeWithTag(TAG_CHAT_MORE).performClick()
        composeRule.onNodeWithTag(TAG_CHAT_DELETE).performClick()
        composeRule.onNodeWithText(context.getString(R.string.sessions_delete_text, "Logs aufräumen")).assertIsDisplayed()
        assertEquals(emptyList<String>(), manager.calls.toList())
        composeRule.onNodeWithTag(SESSION_DELETE_CONFIRM_TAG).performClick()
        composeRule.waitUntil(5_000) { backs.get() == 1 }

        assertEquals(listOf("delete:chat-1"), manager.calls.toList())
    }
}
