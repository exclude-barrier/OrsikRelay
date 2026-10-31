package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
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
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_TITLE
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.chat.ToolActivityChip
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the chat design refresh:
 *
 * - the header is a Material 3 top app bar with a back icon and the
 *   session title;
 * - the composer exposes paperclip and send as icon buttons with accessible
 *   labels and keeps the existing send gate;
 * - the tool-activity chip is a static, non-clickable pill.
 */
class ChatDesignUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerShowsTheSessionTitle() {
        setScreen(sessionTitle = "Projekt Alpha")

        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("Projekt Alpha").assertIsDisplayed()
    }

    @Test
    fun headerFallsBackToTheGenericChatTitle() {
        setScreen()

        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_title)).assertIsDisplayed()
    }

    @Test
    fun headerReadsTheTitleWhenNoneWasPassed() {
        // A notification tap or /branch opens the chat without a title.
        setScreen(titleLookup = { "Vom Server" })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Vom Server").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun passedTitleIsNotReadAgain() {
        setScreen(sessionTitle = "Projekt Alpha", titleLookup = { throw AssertionError("no lookup") })

        composeRule.onNodeWithText("Projekt Alpha").assertIsDisplayed()
    }

    @Test
    fun backIconInvokesTheBackAction() {
        val invoked = AtomicBoolean(false)
        setScreen(onBack = { invoked.set(true) })

        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_back))
            .performClick()
        assertTrue(invoked.get())
    }

    @Test
    fun composerIconsCarryLabelsAndTheSendGateStillApplies() {
        setScreen()

        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_attachment_add))
            .assertIsDisplayed()
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.chat_send))
        send.assertIsDisplayed()
        send.assertIsNotEnabled()

        composeRule.onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Hallo")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).assertIsEnabled()
    }

    @Test
    fun headerAndComposerRenderInDarkTheme() {
        setScreen(darkTheme = true)

        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_send))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_attachment_add))
            .assertIsDisplayed()
    }

    @Test
    fun toolChipIsAStaticPill() {
        composeRule.setContent {
            HermesTheme {
                ToolActivityChip(label = context.getString(R.string.chat_tool_activity_history))
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_tool_activity_history))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    private fun setScreen(
        sessionTitle: String? = null,
        darkTheme: Boolean = false,
        onBack: () -> Unit = {},
        titleLookup: (suspend () -> String?)? = null
    ) {
        composeRule.setContent {
            HermesTheme(darkTheme = darkTheme) {
                ChatScreen(
                    historySource = MessageHistorySource { _, _ ->
                        MessageHistoryResult.Success(MessagePage(emptyList()))
                    },
                    runController = RunSessionController(
                        chatSource = StreamingChatSource { _, _, _ ->
                            kotlinx.coroutines.flow.emptyFlow()
                        },
                        serviceStarter = noOpServiceStarter,
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = runStopRequesterThatIsNeverReached,
                    runSteerRequester = runSteerRequesterThatIsNeverReached,
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
                    sessionTitle = sessionTitle,
                    sessionTitleLookup = titleLookup,
                    onBack = onBack
                )
            }
        }
        // The empty-session status proves the screen is composed.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.chat_empty))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private val noOpServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val runStopRequesterThatIsNeverReached = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in this test")
        }
    }

    private val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing {
            throw AssertionError("run-steer must not be reachable in this test")
        }
    }
}
