package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.ExecuteSlashCommand
import io.github.excludebarrier.orsikrelay.domain.chat.ModelMemory
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsResult
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsSource
import io.github.excludebarrier.orsikrelay.domain.chat.SessionModelOption
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommand
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandResult
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
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_FIELD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_MODEL_PICKER_LIST
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_MODEL_PICKER_STATUS
import io.github.excludebarrier.orsikrelay.ui.chat.modelRowTag
import io.github.excludebarrier.orsikrelay.ui.components.ComposerDockTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `/model` without an "apply" step: the list above the field, the chip,
 * filtering, an immediate lock with undo, the keyboard and the states.
 */
@OptIn(ExperimentalTestApi::class)
class ModelPickerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val options = listOf(
        SessionModelOption("anthropic", "claude-sonnet"),
        SessionModelOption("anthropic", "claude-opus"),
        SessionModelOption("openai", "gpt-5.5")
    )
    private val writes = Collections.synchronizedList(mutableListOf<String>())

    private class Memory(var current: String? = null) : ModelMemory {
        override fun current(profileId: String, sessionId: String) = current
        override fun recent(profileId: String): List<String> = listOfNotNull(current)
        override fun remember(profileId: String, sessionId: String, qualified: String) {
            current = qualified
        }
    }

    private fun show(
        models: ModelOptionsSource = ModelOptionsSource { ModelOptionsResult.Loaded(options) },
        memory: ModelMemory = Memory(),
        chat: StreamingChatSource = StreamingChatSource { _, _, _ -> emptyFlow() }
    ) {
        compose.setContent {
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
                    serverName = "Heimserver",
                    onBack = {},
                    slashSource = SlashCommandSource { setOf(SlashCommand.MODEL) },
                    slashExecutor = ExecuteSlashCommand { _, _, _, argument ->
                        writes += argument
                        SlashCommandResult.Done
                    },
                    modelOptionsSource = models,
                    modelMemory = memory
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.chat_empty)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openList() {
        // Replace, not append: a sent draft stays until the run is confirmed.
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextReplacement("/model ")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(ComposerDockTags.PREFIX).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The list is lazy and, with the keyboard up, short: scroll the row in. */
    private fun waitForRow(qualified: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(TAG_MODEL_PICKER_LIST).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TAG_MODEL_PICKER_LIST).performScrollToNode(hasTestTag(modelRowTag(qualified)))
    }

    private fun fieldText(): String =
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun theListOpensWithAChipCountsAndGroupsByProvider() {
        show()
        openList()
        waitForRow("openai::gpt-5.5")

        compose.onNodeWithTag(ComposerDockTags.PREFIX).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.slash_model_close)).assertIsDisplayed()
        assertEquals("", fieldText())
        compose.onNodeWithText(context.getString(R.string.slash_model_search_hint)).assertExists()
        compose.onNodeWithText("3", substring = true).assertExists()
        // Without history there is no "current & recent"; gpt-5.5 sits under
        // its provider's title (plus its own provider line).
        compose.onAllNodesWithText(context.getString(R.string.slash_models_recent)).assertCountEquals(0)
        compose.onAllNodesWithText("openai").assertCountEquals(2)
    }

    @Test
    fun typingFiltersTheList() {
        show()
        openList()
        waitForRow("openai::gpt-5.5")
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextInput("son")

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(modelRowTag("openai::gpt-5.5")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(modelRowTag("anthropic::claude-sonnet")).assertIsDisplayed()
        compose.onAllNodesWithTag(modelRowTag("anthropic::claude-opus")).assertCountEquals(0)
    }

    @Test
    fun aTapLocksAtOnceAndUndoRestoresThePreviousModel() {
        show(memory = Memory(current = "openai::gpt-5.5"))
        openList()
        waitForRow("anthropic::claude-opus")
        compose.onNodeWithTag(modelRowTag("anthropic::claude-opus")).performClick()

        compose.waitUntil(5_000) { writes.isNotEmpty() }
        assertEquals(listOf("anthropic::claude-opus"), writes.toList())
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.sessions_undo)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("gpt-5.5 → claude-opus", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.slash_model_changed, "claude-opus")).assertExists()
        assertEquals("", fieldText())

        compose.onNodeWithText(context.getString(R.string.sessions_undo)).performClick()
        compose.waitUntil(5_000) { writes.size == 2 }
        assertEquals("openai::gpt-5.5", writes[1])
    }

    @Test
    fun theKeyboardMarksLocksAndCloses() {
        show()
        openList()
        waitForRow("anthropic::claude-sonnet")
        val field = compose.onNodeWithTag(TAG_COMPOSER_FIELD)
        field.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag(modelRowTag("anthropic::claude-opus")).assertIsSelected()
        field.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { writes.isNotEmpty() }
        assertEquals(listOf("anthropic::claude-opus"), writes.toList())

        openList()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ComposerDockTags.PREFIX).fetchSemanticsNodes().isEmpty() }
        assertEquals("", fieldText())

        openList()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performKeyInput { pressKey(Key.Backspace) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ComposerDockTags.PREFIX).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theChipsCrossClosesTheList() {
        show()
        openList()
        compose.onNodeWithTag(ComposerDockTags.PREFIX).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ComposerDockTags.PREFIX).fetchSemanticsNodes().isEmpty() }
        assertEquals("", fieldText())
    }

    @Test
    fun aShortPrefixAndSendOpensTheList() {
        show()
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextInput("/mo")
        compose.onNodeWithTag(TAG_COMPOSER_SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ComposerDockTags.PREFIX).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, writes.size)
    }

    @Test
    fun loadingFailedEmptyAndNoMatchAreStated() {
        val release = CountDownLatch(1)
        show(models = ModelOptionsSource {
            release.await(5, TimeUnit.SECONDS)
            ModelOptionsResult.Loaded(emptyList())
        })
        openList()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(TAG_MODEL_PICKER_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.slash_models_loading)).assertIsDisplayed()
        release.countDown()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.slash_models_empty))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailedCatalogIsStated() {
        show(models = ModelOptionsSource { ModelOptionsResult.Failed })
        openList()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.slash_models_failed))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFilterWithoutHitsSaysSo() {
        show()
        openList()
        waitForRow("openai::gpt-5.5")
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextReplacement("zzz")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.slash_models_no_match, "zzz"))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun whileHermesWorksTheRowsAreDimmedAndLocked() {
        show(chat = StreamingChatSource { _, _, _ -> flow { awaitCancellation() } })
        compose.onNodeWithTag(TAG_COMPOSER_FIELD).performTextInput("Hallo")
        compose.onNodeWithTag(TAG_COMPOSER_SEND).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.chat_header_working)).fetchSemanticsNodes().isNotEmpty()
        }
        openList()
        waitForRow("openai::gpt-5.5")
        compose.onNodeWithText(context.getString(R.string.slash_models_locked_run)).assertIsDisplayed()
        compose.onNodeWithTag(modelRowTag("openai::gpt-5.5")).assertIsNotEnabled()
        assertEquals(0, writes.size)
    }
}
