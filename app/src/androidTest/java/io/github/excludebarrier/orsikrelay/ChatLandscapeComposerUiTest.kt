package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentKind
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.COMPACT_COMPOSER_MAX_HEIGHT
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Height-constrained landscape window with the keyboard open.
 *
 * With the Gboard landscape IME the chat root shrinks to roughly the height of
 * the header plus the composer (measured: 394 px of a 1080 px display with an
 * IME inset of 686 px). Before the compact composer existed, header + attachment
 * chip + input row no longer fit and the input row collapsed to zero height, so
 * the text field, "Anhang" and "Senden" disappeared behind the keyboard.
 *
 * The IME inset is injected deterministically (the real keyboard height of the
 * landscape Gboard), the layout is the production [ChatScreen], and every
 * scenario asserts that the composer stays fully above the keyboard edge.
 */
class ChatLandscapeComposerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Injected IME inset. Derived from the display height so the chat root is
     * left with [AVAILABLE_DP] dp - well below
     * [io.github.excludebarrier.orsikrelay.ui.chat.COMPACT_COMPOSER_MAX_HEIGHT] - on every
     * device (a hardcoded pixel value would silently stop exercising the
     * compact composer on a device with a different resolution or density).
     */
    private val imeBottom: Int by lazy {
        val metrics = composeRule.activity.resources.displayMetrics
        val target = (AVAILABLE_DP * metrics.density).toInt()
        (metrics.heightPixels - target).coerceAtLeast(1)
    }

    @After
    fun clearStagedAttachment() {
        ChatAttachmentDraftHolder.clear(PROFILE_ID, SESSION_ID)
    }

    @Test
    fun composerStaysAboveTheKeyboardWithoutAttachment() {
        enterLandscape(attachment = false, multiline = false)
        assertComposerAboveKeyboard()
    }

    @Test
    fun composerStaysAboveTheKeyboardWithAttachment() {
        enterLandscape(attachment = true, multiline = false)
        assertComposerAboveKeyboard()
    }

    @Test
    fun composerStaysAboveTheKeyboardWithMultilineDraft() {
        enterLandscape(attachment = false, multiline = true)
        assertComposerAboveKeyboard()
    }

    @Test
    fun composerStaysAboveTheKeyboardWithAttachmentAndMultilineDraft() {
        enterLandscape(attachment = true, multiline = true)
        assertComposerAboveKeyboard()
    }

    // The same two attachment cases with a non-empty timeline: there the
    // message list is the flexible region instead of the empty-session status
    // line, so both flex paths are covered.

    @Test
    fun composerStaysAboveTheKeyboardWithMessagesAndAttachment() {
        enterLandscape(attachment = true, multiline = false, withMessages = true)
        assertComposerAboveKeyboard()
    }

    @Test
    fun composerStaysAboveTheKeyboardWithMessagesAttachmentAndMultilineDraft() {
        enterLandscape(attachment = true, multiline = true, withMessages = true)
        assertComposerAboveKeyboard()
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun enterLandscape(
        attachment: Boolean,
        multiline: Boolean,
        withMessages: Boolean = false
    ) {
        if (attachment) stageAttachment()
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Requesting a new orientation recreates the host activity, so the
        // window configuration is applied only AFTER the relaunch settled -
        // otherwise the runnable can land on the instance that is about to be
        // destroyed and the surviving activity would not mirror production.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }
        assertTrue(
            "the host activity must be in landscape before the layout is measured",
            composeRule.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        )
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            // Mirror the production MainActivity (adjustResize): the window is
            // never panned, the chat root consumes the IME inset once.
            composeRule.activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        composeRule.waitForIdle()

        setScreen(withMessages = withMessages)
        if (multiline) {
            composeRule.onNodeWithText(INPUT_LABEL).performTextInput("eins\nzwei\ndrei")
            composeRule.waitForIdle()
        }
        // Typing focuses the field, which can make the window redispatch the
        // REAL keyboard insets (the landscape Gboard is as tall as this whole
        // window). Wait until those are gone before injecting the measured
        // inset, so the scenario is deterministic.
        hideRealImeAndWait()
        dispatchImeInsets(imeBottom)
    }

    private fun hideRealImeAndWait() {
        val activity = composeRule.activity
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val imeShown = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            if (imeShown == 0) return
            Thread.sleep(100)
        }
        composeRule.waitForIdle()
    }

    private fun setScreen(withMessages: Boolean) {
        composeRule.setContent {
            HermesAppSurface {
                HermesTheme {
                    ChatScreen(
                        historySource = MessageHistorySource { _, _ ->
                            MessageHistoryResult.Success(
                                MessagePage(if (withMessages) messages() else emptyList())
                            )
                        },
                        runController = RunSessionController(
                            chatSource = StreamingChatSource { _, _, _ -> emptyFlow() },
                            serviceStarter = NoOpServiceStarter,
                            notificationCoordinator = NoOpNotificationCoordinator
                        ),
                        runStopRequester = NoOpStopRequester(),
                        runSteerRequester = NoOpSteerRequester(),
                        chatDraftRepository = NoOpChatDraftRepository(),
                        attachmentSource = NoopChatAttachmentSource,
                        profileId = PROFILE_ID,
                        sessionId = SESSION_ID,
                        onBack = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // In the compact regime the flexible middle region is not composed at
        // all, so only the header and the composer can be awaited here.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(HEADER_LABEL).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(INPUT_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The whole composer - input row, picker and send action, and the attachment
     * chip with its remove action - must sit completely above the keyboard top
     * edge (the injected IME inset), with a positive height.
     */
    private fun assertComposerAboveKeyboard() {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val keyboardTop = root.height.toInt() - imeBottom
        val availableDp = with(composeRule.activity.resources.displayMetrics) {
            (keyboardTop / density)
        }
        assertTrue(
            "the scenario must exercise the compact composer: available " +
                "$availableDp dp vs threshold $COMPACT_COMPOSER_MAX_HEIGHT",
            availableDp < COMPACT_COMPOSER_MAX_HEIGHT.value
        )

        composeRule.onNodeWithText(HEADER_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(INPUT_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(SEND_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(ADD_LABEL).assertIsDisplayed()

        val labels = mutableListOf(INPUT_LABEL, SEND_LABEL, ADD_LABEL)
        if (ChatAttachmentDraftHolder.get(PROFILE_ID, SESSION_ID) != null) {
            labels += listOf(ATTACHMENT_NAME, ATTACHMENT_SIZE, REMOVE_LABEL)
        }
        labels.forEach { label ->
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label must keep a positive height, was ${bounds.height}",
                bounds.height > 0f
            )
            assertTrue(
                "$label bottom ${bounds.bottom} must stay above the keyboard top $keyboardTop",
                bounds.bottom <= keyboardTop
            )
        }
    }

    private fun stageAttachment() {
        ChatAttachmentDraftHolder.put(
            PROFILE_ID,
            SESSION_ID,
            ChatAttachmentDraft(
                localId = "local-1",
                contentUri = "content://media/external/images/media/42",
                displayName = ATTACHMENT_NAME,
                mimeType = "image/png",
                sizeBytes = 1_500_000L,
                kind = ChatAttachmentKind.IMAGE
            )
        )
    }

    /** Injects the IME inset; status/nav bars keep their real values. */
    private fun dispatchImeInsets(ime: Int) {
        val rootInsets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
        val statusTop = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val navBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .build()
        composeRule.activity.runOnUiThread {
            val content = composeRule.activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        composeRule.waitForIdle()
        Thread.sleep(300)
        composeRule.waitForIdle()
    }

    private fun messages(): List<HermesChatMessage> = listOf(
        HermesChatMessage(
            id = 1L,
            role = MessageRole.USER,
            content = "Fixture-Frage",
            timestampSeconds = null,
            toolName = null
        ),
        HermesChatMessage(
            id = 2L,
            role = MessageRole.ASSISTANT,
            content = "Lange Antwort: " +
                "Das ist eine bewusst lange Assistant-Nachricht, damit die Timeline " +
                "mehrere Zeilen füllt und der Scroll-Verhalten bei IME-Öffnung " +
                "sichtbar bleibt.",
            timestampSeconds = null,
            toolName = null
        )
    )

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

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val HEADER_LABEL = "Chat"
        /** Height the chat root is left with in the landscape+IME scenario. */
        const val AVAILABLE_DP = 150f
        const val INPUT_LABEL = "Nachricht"
        const val SEND_LABEL = "Senden"
        const val ADD_LABEL = "Anhang"
        const val REMOVE_LABEL = "Entfernen"
        const val ATTACHMENT_NAME = "holiday.png"
        const val ATTACHMENT_SIZE = "1.4 MiB"
    }
}
