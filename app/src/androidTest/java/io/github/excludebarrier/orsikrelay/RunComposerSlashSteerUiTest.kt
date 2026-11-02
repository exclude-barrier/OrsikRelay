package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommand
import io.github.excludebarrier.orsikrelay.ui.chat.ComposerBar
import io.github.excludebarrier.orsikrelay.ui.chat.RunComposerControls
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_FIELD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_STOP
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandSource
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
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

/** While a run is bound, /steer replaces the separate "Kurskorrektur" row when the server offers it. */
class RunComposerSlashSteerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(commands: Set<SlashCommand>) {
        composeRule.setContent {
            HermesTheme {
                ComposerBar(
                    draft = "", onDraftChange = {}, sendEnabled = false, onSend = {}, turnActive = true,
                    attachment = null, attachmentThumbnail = null, attachmentBusy = false, attachmentErrorLabel = null,
                    attachmentAddLabel = "add", attachmentRemoveLabel = "remove", attachmentBusyLabel = "busy",
                    onPickAttachment = {}, onRemoveAttachment = {},
                    runControls = RunComposerControls("", {}, false, {}, false, {}),
                    slashCommands = commands,
                    // The screen always passes the compact row's run controls;
                    // outside the compact regime they must not show up.
                    compactRunControls = { androidx.compose.material3.Text("compact-controls") }
                )
            }
        }
    }

    @Test
    fun withSlashSteerTheRunHasOneFieldAndAStopAction() {
        show(setOf(SlashCommand.STEER, SlashCommand.STOP))

        composeRule.onNodeWithTag("chatSteerInput").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.chat_input_running_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_COMPOSER_FIELD).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_RUN_STOP).assertIsDisplayed()
        composeRule.onNodeWithText("compact-controls").assertDoesNotExist()
    }

    @Test
    fun aBridgeWithoutSlashSteerKeepsTheSteerRow() {
        show(emptySet())

        composeRule.onNodeWithTag("chatSteerInput").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RUN_STOP).assertIsDisplayed()
    }

    @Test
    fun theChatShowsNoSteerRowDuringARealRunWhenTheServerOffersSlashSteer() {
        // A run that starts and stays open (the device check found the steer
        // field again inside the composer row).
        val chat = StreamingChatSource { _, _, _ ->
            flow {
                emit(ChatTurnEvent.RunStarted("run_synthetic"))
                awaitCancellation()
            }
        }
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = MessageHistorySource { _, _ -> MessageHistoryResult.Success(MessagePage(emptyList())) },
                    runController = RunSessionController(
                        chatSource = chat,
                        serviceStarter = object : RunForegroundServiceStarter {
                            override fun start(profileId: String, sessionId: String) {}
                            override fun stop() {}
                        },
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = object : RunStopRequester {
                        override suspend fun stopRun(profileId: String, runId: String): Nothing = throw AssertionError()
                    },
                    runSteerRequester = object : RunSteerRequester {
                        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing =
                            throw AssertionError()
                    },
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile",
                    sessionId = "fixture-session",
                    onBack = {},
                    slashSource = SlashCommandSource { setOf(SlashCommand.STEER, SlashCommand.STOP) }
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.chat_empty)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_COMPOSER_FIELD).performTextInput("Hallo")
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("chatSteerInput").assertDoesNotExist()
        composeRule.onNodeWithTag("chatSteerSend").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.chat_input_running_hint)).assertIsDisplayed()
    }
}
