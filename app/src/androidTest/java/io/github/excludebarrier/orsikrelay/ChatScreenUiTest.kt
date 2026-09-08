package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
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
        composeRule.onNodeWithText("Nachrichten werden geladen…").assertIsDisplayed()
        release.countDown()
        waitForText("Diese Session enthält noch keine Nachrichten.")
    }

    @Test
    fun allRolesAndToolNameAreRenderedWithText() {
        setScreen(MessageHistorySource { _, _ ->
            success(
                listOf(
                    message(1, MessageRole.USER, "Fixture-Frage"),
                    message(2, MessageRole.ASSISTANT, "Fixture-Antwort"),
                    message(3, MessageRole.SYSTEM, "Fixture-System"),
                    message(4, MessageRole.TOOL, "Fixture-Ergebnis", "fixture_tool")
                )
            )
        })

        waitForText("Fixture-Antwort")
        listOf("Du", "Hermes", "System", "Tool: fixture_tool").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Fixture-Frage", "Fixture-Antwort", "Fixture-System", "Fixture-Ergebnis").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
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

        waitForText("Die Nachrichten konnten nicht geladen werden.")
        assertEquals(1, calls.get())
        composeRule.onNodeWithText("Erneut versuchen").performClick()
        waitForText("Nach Retry")
        assertEquals(2, calls.get())
    }

    @Test
    fun backButtonInvokesBackAction() {
        val invoked = AtomicBoolean(false)
        setScreen(
            source = MessageHistorySource { _, _ -> success(emptyList()) },
            onBack = { invoked.set(true) }
        )

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Zurück").performClick()
        assertTrue(invoked.get())
    }

    private fun setScreen(
        source: MessageHistorySource,
        chatSource: StreamingChatSource = streamingSourceThatNeverEmits,
        onBack: () -> Unit = {},
        runStopRequester: RunStopRequester = runStopRequesterThatIsNeverReached
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    chatSource = chatSource,
                    runStopRequester = runStopRequester,
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
    }
}
