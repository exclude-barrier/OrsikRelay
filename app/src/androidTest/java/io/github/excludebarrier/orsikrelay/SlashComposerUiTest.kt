package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.excludebarrier.orsikrelay.domain.chat.*
import io.github.excludebarrier.orsikrelay.ui.components.*
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_FIELD
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import io.github.excludebarrier.orsikrelay.ui.chat.ComposerBar

class SlashComposerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leadingSlashOpensFiltersInsertsAndKeepsFocusWithoutSending() {
        var sends = 0
        compose.setContent {
            var text by remember { mutableStateOf("") }
            MaterialTheme {
                AgentComposerDock(text, { text = it }, "Message", true, true, false, { sends++ },
                    suggestions = slashSuggestions(text, SlashCommand.entries.toSet()).map { ComposerSuggestion("/${it.word}") },
                    onSuggestion = { text = "$it " })
            }
        }
        compose.onNodeWithTag(ComposerDockTags.INPUT).performTextInput("text /")
        compose.onNodeWithTag("slash_menu").assertDoesNotExist()
        compose.onNodeWithTag(ComposerDockTags.INPUT).performTextReplacement("/")
        compose.onNodeWithTag("slash:/title").assertExists()
        compose.onNodeWithTag(ComposerDockTags.INPUT).performTextReplacement("/ti")
        compose.onNodeWithTag("slash:/new").assertDoesNotExist()
        compose.onNodeWithTag("slash:/title").performClick()
        assertEquals("/title ", compose.onNodeWithTag(ComposerDockTags.INPUT)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag(ComposerDockTags.INPUT).assertIsFocused()
        compose.onNodeWithTag(ComposerDockTags.INPUT).performTextInput("New title")
        assertEquals("/title New title", compose.onNodeWithTag(ComposerDockTags.INPUT)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun productionComposerShowsDisabledRunCommandsAndInsertsFilteredModelChoice() {
        var sends = 0
        compose.setContent {
            var text by remember { mutableStateOf("/") }
            MaterialTheme {
                ComposerBar(
                    draft = text, onDraftChange = { text = it }, sendEnabled = true, onSend = { sends++ },
                    turnActive = false, attachment = null, attachmentThumbnail = null, attachmentBusy = false,
                    attachmentErrorLabel = null, attachmentAddLabel = "Attach", attachmentRemoveLabel = "Remove",
                    attachmentBusyLabel = "Loading", onPickAttachment = {}, onRemoveAttachment = {},
                    compact = true,
                    slashCommands = SlashCommand.entries.toSet(), onSlashSelected = { text = it.insertion },
                    modelOptions = listOf(SessionModelOption("provider", "org/model"), SessionModelOption("provider", "other"))
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("slash:/stop").assertIsNotEnabled()
            .assertTextContains(context.getString(R.string.slash_no_run))
        compose.onNodeWithTag("slash:/title").assertIsEnabled()
            .assertTextContains(context.getString(R.string.slash_desc_title))
        compose.onNodeWithTag("slash:/model").performScrollTo().performClick()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextReplacement("/model org/")
        compose.onNodeWithTag("slash:/model provider::other").assertDoesNotExist()
        compose.onNodeWithTag("slash:/model provider::org/model")
            .assertTextContains("org/model").assertTextContains("provider")
        compose.onNodeWithTag("slash:/model provider::org/model").performClick()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).assertIsFocused()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).assertIsDisplayed()
        assertEquals("/model provider::org/model ", compose.onNodeWithTag(TAG_COMPOSER_FIELD)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(0, sends) }
    }
}
