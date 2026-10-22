package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the chat bubble arrangement:
 *
 * - the user's message is right-aligned and capped at 85 % of the content
 *   width, Hermes' message is left-aligned and capped at 92 %;
 * - a history TOOL row still renders only its safe label (no content node,
 *   no bubble);
 * - the bubbles grow with the content at font scale 2.0 instead of clipping.
 */
class ChatBubbleUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun userBubbleSitsAtTheRightEdgeAndStaysWithin85Percent() {
        setScreen(
            listOf(
                message(1, MessageRole.USER, LONG_USER),
                message(2, MessageRole.ASSISTANT, "Kurze Antwort")
            )
        )
        waitForText(LONG_USER)

        val row = bounds("chatTimelineRow:server:1")
        val bubble = bounds("chatBubble:server:1")
        assertTrue(
            "user bubble right edge must sit on the content edge, gap was ${row.right - bubble.right} px",
            abs(row.right - bubble.right) <= 1f
        )
        assertTrue(
            "user bubble width ${bubble.width} px exceeds 85 % of ${row.width} px",
            bubble.width <= row.width * 0.85f + 1f
        )
        composeRule.onNodeWithText(LONG_USER).assertIsDisplayed()
    }

    @Test
    fun hermesAnswerUsesTheFullWidthWithoutABubble() {
        setScreen(
            listOf(
                message(1, MessageRole.USER, "Kurze Frage"),
                message(2, MessageRole.ASSISTANT, LONG_ASSISTANT)
            )
        )
        waitForText(LONG_ASSISTANT)

        val row = bounds("chatTimelineRow:server:2")
        val body = bounds("chatAssistantBody:server:2")
        // No assistant bubble exists: the body is plain content on the
        // surface. Design v2 puts Hermes' 30 dp avatar and a 10 dp gap in
        // front of it; the body takes all the remaining width.
        composeRule.onAllNodesWithTag("chatBubble:server:2").assertCountEquals(0)
        val avatarPx = with(composeRule.density) { HERMES_AVATAR_INDENT.toPx() }
        assertTrue(
            "Hermes body must start after the avatar, gap was ${body.left - row.left} px",
            abs(body.left - row.left - avatarPx) <= 1f
        )
        assertTrue(
            "Hermes body must end on the content edge, gap was ${row.right - body.right} px",
            abs(row.right - body.right) <= 1f
        )
        // The visible sender label is gone, but the accessibility label stays.
        composeRule.onAllNodesWithText(context.getString(R.string.chat_role_assistant))
            .assertCountEquals(0)
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.chat_role_assistant)
        ).assertExists()
    }

    @Test
    fun userMessageWithAnImageShowsTheImageCountAndItsText() {
        setScreen(
            listOf(
                message(1, MessageRole.USER, "Was ist das?").copy(imageCount = 1),
                message(2, MessageRole.USER, "").copy(imageCount = 2)
            )
        )
        val one = context.resources.getQuantityString(R.plurals.chat_message_images, 1, 1)
        val two = context.resources.getQuantityString(R.plurals.chat_message_images, 2, 2)
        waitForText("Was ist das?")
        composeRule.onNodeWithText(one).assertIsDisplayed()
        composeRule.onNodeWithText(two).assertIsDisplayed()
    }

    @Test
    fun historyToolRowRendersOnlyTheSafeLabel() {
        setScreen(
            listOf(
                message(
                    3,
                    MessageRole.TOOL,
                    """{"output":"SECRET_BUBBLE_TOOL_123","exit_code":0}""",
                    "fixture_tool"
                )
            )
        )
        waitForText(context.getString(R.string.chat_role_tool_named, "fixture_tool"))

        composeRule.onNodeWithTag("chatTimelineRow:server:3").assertIsDisplayed()
        composeRule.onAllNodesWithTag("chatBubble:server:3").assertCountEquals(0)
        composeRule.onAllNodesWithText("SECRET_BUBBLE_TOOL_123").assertCountEquals(0)
        composeRule.onAllNodesWithText("""{"output":"SECRET_BUBBLE_TOOL_123","exit_code":0}""")
            .assertCountEquals(0)
    }

    @Test
    fun consecutiveToolCallsCollapseIntoOneExpandableBlock() {
        setScreen(
            listOf(
                message(1, MessageRole.USER, "Prüf den Server"),
                message(2, MessageRole.TOOL, """{"output":"SECRET_GROUP_1"}""", "terminal"),
                message(3, MessageRole.TOOL, """{"output":"SECRET_GROUP_2"}""", "read_file"),
                message(4, MessageRole.TOOL, """{"output":"SECRET_GROUP_3"}""", "browser"),
                message(5, MessageRole.ASSISTANT, "Alles gut.")
            )
        )
        waitForText("Alles gut.")

        val toggle = "chatToolGroupToggle:tools:server:2"
        val count = context.resources.getQuantityString(R.plurals.chat_tool_group_count, 3, 3)
        val more = context.getString(R.string.chat_tool_group_more, 1)
        val done = context.getString(R.string.chat_tool_done)
        composeRule.onNodeWithText("$count · terminal, read_file $more · $done", useUnmergedTree = true).assertIsDisplayed()
        // Collapsed: no single tool line, and never any tool payload.
        composeRule.onAllNodesWithTag("chatToolChip:server:2").assertCountEquals(0)
        composeRule.onAllNodesWithText("SECRET_GROUP_1", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag(toggle).performClick()
        listOf(2, 3, 4).forEach { id -> composeRule.onNodeWithTag("chatToolChip:server:$id").assertIsDisplayed() }
        composeRule.onAllNodesWithText("SECRET_GROUP_2", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag(toggle).performClick()
        composeRule.onAllNodesWithTag("chatToolChip:server:3").assertCountEquals(0)
    }

    @Test
    fun aSingleToolCallStaysAPlainChip() {
        setScreen(
            listOf(
                message(1, MessageRole.TOOL, "", "terminal"),
                message(2, MessageRole.ASSISTANT, "Fertig.")
            )
        )
        waitForText("Fertig.")

        composeRule.onNodeWithTag("chatToolChip:server:1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("chatToolGroupToggle:tools:server:1").assertCountEquals(0)
    }

    @Test
    fun bubblesGrowInsteadOfClippingAtLargeFont() {
        setScreen(
            listOf(
                message(1, MessageRole.USER, LONG_USER),
                message(2, MessageRole.ASSISTANT, LONG_ASSISTANT)
            ),
            fontScale = 2.0f
        )
        waitForText(LONG_USER)
        waitForText(LONG_ASSISTANT)

        assertFalse("user text must not be ellipsized", isTextEllipsized(LONG_USER))
        assertFalse("Hermes text must not be ellipsized", isTextEllipsized(LONG_ASSISTANT))

        val userRow = bounds("chatTimelineRow:server:1")
        val userBubble = bounds("chatBubble:server:1")
        assertTrue(
            "user bubble width ${userBubble.width} px exceeds 85 % of ${userRow.width} px",
            userBubble.width <= userRow.width * 0.85f + 1f
        )
        val hermesRow = bounds("chatTimelineRow:server:2")
        val hermesBody = bounds("chatAssistantBody:server:2")
        val avatarPx = with(composeRule.density) { HERMES_AVATAR_INDENT.toPx() }
        assertTrue(
            "Hermes body width ${hermesBody.width} px must fill the row width ${hermesRow.width} px after the avatar",
            abs(hermesBody.width - (hermesRow.width - avatarPx)) <= 1f
        )
    }

    private fun setScreen(
        messages: List<HermesChatMessage>,
        fontScale: Float = 1f
    ) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                HermesTheme {
                    ChatScreen(
                        historySource = MessageHistorySource { _, _ ->
                            MessageHistoryResult.Success(MessagePage(messages))
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
                        profileId = "fixture-profile-id",
                        sessionId = "fixture-session-id",
                        onBack = {}
                    )
                }
            }
        }
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun isTextEllipsized(text: String): Boolean {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("no GetTextLayoutResult for '$text'", action != null)
        val layouts = ArrayList<TextLayoutResult>()
        action!!.action?.invoke(layouts)
        assertTrue("no TextLayoutResult for '$text'", layouts.isNotEmpty())
        return layouts.any { layout ->
            layout.lineCount >= 1 &&
                (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) }
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
        content: String,
        toolName: String? = null
    ) = HermesChatMessage(
        id = id,
        role = role,
        content = content,
        timestampSeconds = null,
        toolName = toolName
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
        const val LONG_USER =
            "Eine längere Nutzerfrage, die über mehrere Zeilen umbrechen muss, damit die Blase ihre Höchstbreite erreicht und rechtsbündig abschließt."
        const val LONG_ASSISTANT =
            "Eine längere Antwort von Hermes, die ebenfalls über mehrere Zeilen umbrechen muss, damit die Blase ihre Höchstbreite erreicht und linksbündig beginnt."
    }
}

/** Hermes' avatar (30 dp) plus the gap (10 dp) in front of every answer. */
private val HERMES_AVATAR_INDENT = 40.dp
