package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentMetadata
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentKind
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentRules
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented Compose tests for the REAL attachment chip of the production
 * [ChatScreen]. The picked attachment is staged through the production
 * process-scoped [ChatAttachmentDraftHolder] — the exact value the real
 * `onAttachmentPicked` path stores after picker validation — so the real
 * view model rehydrates it at construction and the real composer renders the
 * chip. All seams are synthetic fakes: no network, no picker activity, no
 * credentials.
 */
class ChatAttachmentChipUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var session: String? = null
    private val sessionCounter = AtomicInteger()

    /** The holder is process-scoped: drop this test's entry. */
    @After
    fun clearStagedAttachment() {
        val session = this.session
        if (session != null) {
            ChatAttachmentDraftHolder.clear(PROFILE_ID, session)
        }
    }

    /**
     * Stages one validated attachment draft for a fresh session — the same
     * value the real `onAttachmentPicked` path would store — so the real
     * view model rehydrates it when it is constructed.
     */
    private fun stageAttachment(displayName: String, sizeBytes: Long) {
        val session = "chip-" + sessionCounter.incrementAndGet()
        this.session = session
        ChatAttachmentDraftHolder.put(
            PROFILE_ID,
            session,
            ChatAttachmentDraft(
                localId = "local-1",
                contentUri = "content://media/external/images/media/42",
                displayName = displayName,
                mimeType = "image/png",
                sizeBytes = sizeBytes,
                kind = ChatAttachmentKind.IMAGE
            )
        )
    }

    private fun setScreen(
        attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
        chatSource: StreamingChatSource = NoOpTurnSource()
    ) {
        val session = checkNotNull(session)
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = RunSessionController(
                        chatSource = chatSource,
                        serviceStarter = NoOpServiceStarter,
                        notificationCoordinator = NoOpNotificationCoordinator
                    ),
                    runStopRequester = NoOpStopRequester(),
                    runSteerRequester = NoOpSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    attachmentSource = attachmentSource,
                    profileId = PROFILE_ID,
                    sessionId = session,
                    onBack = {}
                )
            }
        }
    }

    private fun history(messages: List<HermesChatMessage>) =
        MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(messages))
        }

    private fun waitForEmptySession() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(EMPTY_SESSION_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun draftText(): String? {
        val nodes = composeRule.onAllNodesWithText("Nachricht").fetchSemanticsNodes()
        return nodes
            .firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
            ?.config[SemanticsProperties.EditableText]
            ?.text
            ?.toString()
    }

    /** The accessible label text of one tagged button (its Text child's label). */
    private fun labelTextOf(tag: String): String {
        val labels = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text]?.forEach { labels.add(it.text.toString()) }
            }
            node.children.forEach { walk(it) }
        }
        walk(composeRule.onNodeWithTag(tag).fetchSemanticsNode())
        return labels.joinToString("")
    }

    /** Every visible text in the tree: Text, editable text and content descriptions. */
    private fun visibleTexts(): List<String> {
        val texts = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text]?.forEach { texts.add(it.text.toString()) }
            }
            if (node.config.contains(SemanticsProperties.EditableText)) {
                node.config[SemanticsProperties.EditableText]?.let { texts.add(it.text.toString()) }
            }
            if (node.config.contains(SemanticsProperties.ContentDescription)) {
                node.config[SemanticsProperties.ContentDescription]?.forEach { texts.add(it) }
            }
            node.children.forEach { walk(it) }
        }
        walk(composeRule.onRoot().fetchSemanticsNode())
        return texts
    }

    // ------------------------------------------------------------------
    // Chip content: name, size, buttons, remove.
    // ------------------------------------------------------------------

    @Test
    fun stagedAttachmentShowsChipWithDisplayNameAndSize() {
        stageAttachment("holiday.png", 1_500_000L)
        setScreen()

        waitForEmptySession()
        // The chip is visible for the staged attachment.
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        // The provider-reported display name is visible as text.
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
        // The formatted size is visible as text (no raw byte count, no path).
        composeRule.onNodeWithText("1.4 MiB").assertIsDisplayed()
    }

    @Test
    fun composerButtonsCarryNonEmptyLabels() {
        stageAttachment("holiday.png", 1_500_000L)
        setScreen()

        waitForEmptySession()
        // The picker button exists, is displayed and carries a non-empty
        // accessible label.
        composeRule.onNodeWithTag(TAG_ADD).assertIsDisplayed()
        assertEquals(ADD_LABEL, labelTextOf(TAG_ADD))
        // The remove button is visible for the chip and labeled.
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        assertEquals(REMOVE_LABEL, labelTextOf(TAG_REMOVE))
    }

    @Test
    fun removeClickDropsChipAndKeepsDraftText() {
        stageAttachment("holiday.png", 1_500_000L)
        setScreen()

        waitForEmptySession()
        composeRule.onNodeWithText("Nachricht").performTextInput("Text bleibt")
        composeRule.onNodeWithTag(TAG_REMOVE).performClick()

        // The chip is gone...
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TAG_REMOVE).fetchSemanticsNodes().isEmpty()
        }
        // ...and the composer text is unchanged.
        assertEquals("Text bleibt", draftText())
        composeRule.waitForIdle()
    }

    @Test
    fun multilineDraftKeepsChipAndSendEnabled() {
        stageAttachment("holiday.png", 1_500_000L)
        setScreen()

        waitForEmptySession()
        composeRule.onNodeWithText("Nachricht").performTextInput("Zeile eins\nZeile zwei\nZeile drei")
        composeRule.waitForIdle()

        // The chip stays rendered next to the multi-line draft and the real
        // send gate (text + attachment) enables the action.
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsEnabled()
        assertEquals("Zeile eins\nZeile zwei\nZeile drei", draftText())
    }

    @Test
    fun longDisplayNameStaysWithinScreenBounds() {
        val longName = "a".repeat(120)
        stageAttachment(longName, 1_500_000L)
        setScreen()

        waitForEmptySession()
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // The chip is a Row whose name column has `weight(1f)`: an overlong
        // name truncates (ellipsis) and must never push the chip off-screen
        // or grow the window.
        val removeBounds = composeRule.onNodeWithTag(TAG_REMOVE).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "chip stays left of the screen edge",
            removeBounds.left >= rootBounds.left
        )
        assertTrue(
            "chip stays right of the screen edge",
            removeBounds.right <= rootBounds.right
        )
        assertTrue(
            "chip width never exceeds the root width",
            removeBounds.width <= rootBounds.width
        )
        // The display name itself is rendered (truncated) inside the screen.
        val nameBounds = composeRule
            .onAllNodesWithText(longName.take(40), substring = true)
            .onFirst()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "name node stays inside the screen",
            nameBounds.left >= rootBounds.left && nameBounds.right <= rootBounds.right
        )
    }

    @Test
    fun bidiDisplayNameIsRenderedSanitized() {
        val raw = "\u202Egpj.exe\u202C\u200B.png"
        // The production gate strips Unicode FORMAT characters (bidi
        // overrides, zero-width marks) from provider-reported names.
        val sanitized = ChatAttachmentRules.sanitizeDisplayName(raw, "image/png")
        assertEquals("gpj.exe.png", sanitized)
        stageAttachment(sanitized, 1_000L)
        setScreen()

        waitForEmptySession()
        // The sanitized result is what the chip shows.
        composeRule.onNodeWithText("gpj.exe.png").assertIsDisplayed()
        // No format character is visible anywhere in the tree.
        val offenders = visibleTexts().filter { it.any { c -> c in FORMAT_CHARS } }
        assertTrue("no U+202E/U+202C/U+200B visible, found: $offenders", offenders.isEmpty())
    }

    @Test
    fun tooLargeReadShowsSizeErrorAndKeepsChipWithoutPost() {
        val chat = NoOpTurnSource()
        val attachment = TooLargeAttachmentSource()
        stageAttachment("holiday.png", 4_000_000L)
        setScreen(attachmentSource = attachment, chatSource = chat)

        waitForEmptySession()
        // An attachment-only turn enables the send action.
        composeRule.onNodeWithText("Senden").assertIsEnabled()
        composeRule.onNodeWithText("Senden").performClick()

        // The closed TooLarge outcome maps to the too-large error label...
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(ERROR_SIZE_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        // ...the bytes were read at send time...
        assertTrue("the send read the attachment bytes", attachment.readCalls.get() >= 1)
        // ...and the chip stays visible for a deliberate retry.
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
        // Nothing was POSTed; the premature user line is dropped again, so
        // the name is visible exactly once (the chip).
        assertEquals(0, chat.calls.get())
        assertEquals(
            "only the chip shows the name, no premature user line",
            1,
            composeRule.onAllNodesWithText("holiday.png", substring = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun uncertainSendKeepsTheStagedAttachmentAndBlocksTheComposer() {
        val chat = ControllableTurnSource()
        stageAttachment("holiday.png", 1_500_000L)
        setScreen(attachmentSource = GoodAttachmentSource(), chatSource = chat)

        waitForEmptySession()
        // An attachment-only turn enables the send action.
        composeRule.onNodeWithText("Senden").assertIsEnabled()
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(chat.opened.await(3, TimeUnit.SECONDS))

        // Uncertain run: no confirmed run ID, but the initial POST may still have
        // been accepted - the outcome is UNKNOWN. The staged attachment (chip AND
        // holder) survives and the composer stays locked: no second POST.
        chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        chat.complete()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TAG_UNCERTAIN).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        assertEquals("no second POST", 1, chat.calls.get())
    }

    @Test
    fun noProviderPathsOrUrisAreVisibleInTheTree() {
        stageAttachment("holiday.png", 1_500_000L)
        setScreen()

        waitForEmptySession()
        composeRule.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        val visible = visibleTexts()
        listOf("content://", "/storage/", "/sdcard/", "file://").forEach { marker ->
            assertTrue(
                "'$marker' must not be visible in the chat tree",
                visible.none { it.contains(marker) }
            )
        }
    }

    // ------------------------------------------------------------------
    // Synthetic seams: no network, no picker, no credentials.
    // ------------------------------------------------------------------

    private object NoOpServiceStarter : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private class NoOpStopRequester : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): RunStopResult =
            RunStopResult.Accepted("run_synthetic")
    }

    private class NoOpSteerRequester : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): RunSteerResult =
            RunSteerResult.Queued("run_synthetic")
    }

    private class NoOpTurnSource : StreamingChatSource {
        val calls = AtomicInteger()

        override fun streamTurn(profileId: String, sessionId: String, message: String): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            return flow { }
        }
    }

    /**
     * Controllable synthetic turn source: the test enqueues events and closes
     * the stream. No network, no credentials.
     */
    private class ControllableTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val opened = CountDownLatch(1)
        @Volatile
        private var activeChannel: kotlinx.coroutines.channels.Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            val channel = kotlinx.coroutines.channels.Channel<ChatTurnEvent>(
                kotlinx.coroutines.channels.Channel.UNLIMITED
            )
            activeChannel = channel
            opened.countDown()
            return flow {
                for (event in channel) {
                    emit(event)
                }
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            activeChannel?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            activeChannel?.close()
        }
    }

    /** A source whose byte read always yields a valid inline data URL. */
    private class GoodAttachmentSource : ChatAttachmentSource {
        override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? =
            ChatAttachmentMetadata("holiday.png", "image/png", 1_500_000L)

        override suspend fun readInlineDataUrl(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): String = "data:image/png;base64,QUJD"

        override suspend fun readInlineDataUrlResult(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): ChatAttachmentReadResult = ChatAttachmentReadResult.DataUrl("data:image/png;base64,QUJD")
    }

    /** A source whose byte read always yields the closed TooLarge outcome. */
    private class TooLargeAttachmentSource : ChatAttachmentSource {
        val readCalls = AtomicInteger()

        override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? = null

        override suspend fun readInlineDataUrl(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): String? = null

        override suspend fun readInlineDataUrlResult(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): ChatAttachmentReadResult {
            readCalls.incrementAndGet()
            return ChatAttachmentReadResult.TooLarge
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val TAG_ADD = "composer_attachment_add"
        const val TAG_REMOVE = "composer_attachment_remove"
        // The uncertain-run notice test tag (ChatScreen's TAG_RUN_UNCERTAIN).
        const val TAG_UNCERTAIN = "chat_run_uncertain"
        const val EMPTY_SESSION_TEXT = "Diese Session enthält noch keine Nachrichten."
        const val ADD_LABEL = "Anhang"
        const val REMOVE_LABEL = "Entfernen"
        const val ERROR_SIZE_LABEL = "Der Anhang ist zu groß. Maximal 5 MiB pro Bild."
        val FORMAT_CHARS = setOf('\u202E', '\u202C', '\u200B')
    }
}
