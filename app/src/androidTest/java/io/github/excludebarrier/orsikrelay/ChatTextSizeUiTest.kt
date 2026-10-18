package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
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
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_BAR
import io.github.excludebarrier.orsikrelay.ui.settings.ChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The chat text size multiplies the rendered message font only: message
 * content (plain user text and Markdown, including headings) grows by 1.15,
 * while the role labels and the composer keep the system font scale.
 */
class ChatTextSizeUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sizeState = mutableStateOf(ChatTextSize.Normal)

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeTextScalesMessageContentButNotLabelsOrComposer() {
        setScreen()
        waitForText(USER_TEXT)
        waitForText(MARKDOWN_HEADING)
        waitForText(MARKDOWN_BODY)

        val normalUserScale = fontScaleOf(USER_TEXT)
        val normalHeadingScale = fontScaleOf(MARKDOWN_HEADING)
        val normalBodyScale = fontScaleOf(MARKDOWN_BODY)
        val normalLabelScale = fontScaleOf(context.getString(R.string.chat_role_user))
        val normalComposerHeight = boundsHeight(TAG_COMPOSER_BAR)

        sizeState.value = ChatTextSize.Large
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fontScaleOf(USER_TEXT) > normalUserScale
        }

        val largeUserScale = fontScaleOf(USER_TEXT)
        val largeHeadingScale = fontScaleOf(MARKDOWN_HEADING)
        val largeBodyScale = fontScaleOf(MARKDOWN_BODY)
        val largeLabelScale = fontScaleOf(context.getString(R.string.chat_role_user))
        val largeComposerHeight = boundsHeight(TAG_COMPOSER_BAR)

        assertScale(normalUserScale, largeUserScale)
        assertScale(normalHeadingScale, largeHeadingScale)
        assertScale(normalBodyScale, largeBodyScale)
        assertEquals(normalLabelScale, largeLabelScale, 0.001f)
        assertEquals(normalComposerHeight, largeComposerHeight, 1f)
    }

    private fun assertScale(normal: Float, large: Float) {
        assertEquals(normal * 1.15f, large, normal * 0.01f)
    }

    private fun fontScaleOf(text: String): Float {
        val node = composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?: error("no GetTextLayoutResult for '$text'")
        val layouts = ArrayList<TextLayoutResult>()
        action.action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: error("no TextLayoutResult for '$text'")
        return layout.layoutInput.density.fontScale
    }

    private fun boundsHeight(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun setScreen() {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = MessageHistorySource { _, _ ->
                        MessageHistoryResult.Success(
                            MessagePage(
                                listOf(
                                    message(1, MessageRole.USER, USER_TEXT),
                                    message(2, MessageRole.ASSISTANT, ASSISTANT_TEXT)
                                )
                            )
                        )
                    },
                    runController = RunSessionController(
                        chatSource = StreamingChatSource { _, _, _ ->
                            kotlinx.coroutines.flow.emptyFlow()
                        },
                        serviceStarter = noOpRunServiceStarter,
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = runStopRequesterThatIsNeverReached,
                    runSteerRequester = runSteerRequesterThatIsNeverReached,
                    chatDraftRepository = NoOpChatDraftRepository(),
                    chatTextSizeController = ChatTextSizeController(sizeState) { },
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
                    onBack = {}
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

    private fun message(
        id: Long,
        role: MessageRole,
        content: String
    ) = HermesChatMessage(
        id = id,
        role = role,
        content = content,
        timestampSeconds = null,
        toolName = null
    )

    private val noOpRunServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val runStopRequesterThatIsNeverReached = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in history-slice tests")
        }
    }

    private val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
        override suspend fun steerRun(
            profileId: String,
            runId: String,
            text: String
        ): Nothing {
            throw AssertionError("run-steer must not be reachable in history-slice tests")
        }
    }

    private companion object {
        const val USER_TEXT = "Kurze Frage"
        const val MARKDOWN_HEADING = "Status"
        const val MARKDOWN_BODY = "Kurze Antwort"
        const val ASSISTANT_TEXT = "# $MARKDOWN_HEADING\n\n$MARKDOWN_BODY"
    }
}
