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
                    slashCommands = commands
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
    }

    @Test
    fun aBridgeWithoutSlashSteerKeepsTheSteerRow() {
        show(emptySet())

        composeRule.onNodeWithTag("chatSteerInput").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RUN_STOP).assertIsDisplayed()
    }
}
