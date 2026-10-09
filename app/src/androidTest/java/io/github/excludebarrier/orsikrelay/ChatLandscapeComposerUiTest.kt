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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentMetadata
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentKind
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
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
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_ADD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_REMOVE
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_FIELD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_STOP
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.channels.Channel
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
 * Height-constrained landscape window with the keyboard open.
 *
 * With the Gboard landscape IME the chat root shrinks to roughly the height of
 * the header plus the composer (measured: 394 px of a 1080 px display with an
 * IME inset of 686 px). The composer then switches to its compact regime: one
 * dense row instead of chip row + input row, because two rows do not fit below
 * the header.
 *
 * This class covers what that regime owes the user:
 * - the composer stays completely above the keyboard edge (no overlap);
 * - the interactive controls keep a full 48 dp touch target (the row is never
 *   squeezed below the Material minimum - only secondary information shrinks);
 * - the run-critical stop and steer controls stay reachable while the timeline
 *   and its status blocks are not composed.
 *
 * The IME inset is injected deterministically; the layout is the production
 * [ChatScreen].
 */
class ChatLandscapeComposerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Injected IME inset. Derived from the display height so the chat root is
     * left with [AVAILABLE_DP] dp - well below
     * [COMPACT_COMPOSER_MAX_HEIGHT] - on every device (a hardcoded pixel value
     * would silently stop exercising the compact composer on a device with a
     * different resolution or density).
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

    // ------------------------------------------------------------------
    // Layout: the composer stays above the keyboard in every combination.
    // ------------------------------------------------------------------

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
    // Accessibility: 48 dp touch targets in the compact row.
    // ------------------------------------------------------------------

    @Test
    fun compactControlsKeepFullTouchTargets() {
        // With an attachment staged: remove + send (the picker is not offered
        // while the single allowed attachment is already there).
        enterLandscape(attachment = true, multiline = true)
        assertComposerAboveKeyboard()
        assertTouchTargets(
            "attachment remove" to TAG_ATTACHMENT_REMOVE,
            "send" to TAG_COMPOSER_SEND
        )
        // The draft field must keep a usable width floor even with a capped
        // long attachment name: the collapsed 48 dp pill must not come back.
        val fieldWidthDp = with(composeRule.activity.resources.displayMetrics) {
            composeRule.onNodeWithTag(TAG_COMPOSER_FIELD).fetchSemanticsNode().boundsInRoot.width / density
        }
        assertTrue(
            "the compact draft field must keep a usable width, was $fieldWidthDp dp",
            fieldWidthDp >= MIN_FIELD_WIDTH_DP
        )
    }

    @Test
    fun compactPickerKeepsAFullTouchTarget() {
        enterLandscape(attachment = false, multiline = false)
        assertComposerAboveKeyboard()
        assertTouchTargets(
            "attachment picker" to TAG_ATTACHMENT_ADD,
            "send" to TAG_COMPOSER_SEND
        )
    }

    private fun assertTouchTargets(vararg controls: Pair<String, String>) {
        controls.forEach { (label, tag) ->
            val heightDp = touchTargetDp(tag)
            assertTrue(
                "the compact $label touch target must stay at least 48 dp, was $heightDp dp",
                heightDp >= MIN_TOUCH_TARGET_DP
            )
        }
    }

    @Test
    fun compactRowKeepsReportingAFailedAttachmentSend() {
        enterLandscape(attachment = true, multiline = true, attachmentSource = TooLargeAttachmentSource())

        // An attachment-only turn is a legitimate request, so send is enabled.
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(SIZE_ERROR_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(SIZE_ERROR_LABEL).assertIsDisplayed()
        // The chip stays for a deliberate retry, and the compact row stays the
        // measured regime.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).assertIsDisplayed()
        composeRule.onNodeWithText(SIZE_ERROR_LABEL).assertIsDisplayed()
        dispatchImeInsets(imeBottom)
        assertCompactRegime()
        // The error line must not push the row off the keyboard edge, and the
        // controls must keep their touch targets in this worst case.
        assertNodesAboveKeyboard(TAG_ATTACHMENT_REMOVE, TAG_COMPOSER_SEND)
        assertTouchTargets(
            "attachment remove" to TAG_ATTACHMENT_REMOVE,
            "send" to TAG_COMPOSER_SEND
        )
    }

    // ------------------------------------------------------------------
    // Run controls: stop and steer must not disappear in the compact regime.
    // ------------------------------------------------------------------

    @Test
    fun compactRunControlsAreAbsentWithoutAnActiveRun() {
        enterLandscape(attachment = false, multiline = false)

        assertTrue(
            "no stop control may exist without a bound run",
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "no steer control may exist without a bound run",
            composeRule.onAllNodesWithTag(STEER_INPUT_TAG).fetchSemanticsNodes().isEmpty()
        )
        // The ordinary compact composer is what is shown instead.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_ADD).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).assertIsDisplayed()
    }

    @Test
    fun compactRunControlsStayReachableWhileARunIsActive() {
        val source = FakeTurnSource()
        val stopper = RecordingStopRequester()
        val steerer = RecordingSteerRequester()
        enterLandscape(
            attachment = false,
            multiline = false,
            chatSource = source,
            stopRequester = stopper,
            steerRequester = steerer
        )

        composeRule.onNodeWithText(INPUT_LABEL).performTextInput("Stop-Test")
        settleAfterTyping()
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).performClick()
        assertTrue("the turn must reach the streaming source", source.opened.await(3, TimeUnit.SECONDS))
        // Synthetic focus changes make the window redispatch its REAL insets
        // (keyboard closed here), so the measured inset has to be re-applied
        // before the compact regime is asserted on.
        dispatchImeInsets(imeBottom)
        assertCompactRegime()

        // Before the server confirms the run id the controls stay hidden.
        assertTrue(
            "no stop control before RunStarted",
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isEmpty()
        )

        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        dispatchImeInsets(imeBottom)
        assertCompactRegime()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(TAG_RUN_STOP).assertIsDisplayed()
        assertTrue(
            "the compact stop touch target must stay at least 48 dp",
            touchTargetDp(TAG_RUN_STOP) >= MIN_TOUCH_TARGET_DP
        )
        composeRule.onNodeWithTag(STEER_INPUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(STEER_SEND_TAG).assertIsDisplayed()
        assertTrue(
            "the compact steer touch target must stay at least 48 dp",
            touchTargetDp(STEER_SEND_TAG) >= MIN_TOUCH_TARGET_DP
        )

        // The run controls sit inside the same row: they must stay above the
        // keyboard edge exactly like the ordinary composer.
        assertNodesAboveKeyboard(TAG_RUN_STOP, STEER_INPUT_TAG, STEER_SEND_TAG)

        // And both are actually operable from the compact row.
        composeRule.onNodeWithTag(STEER_INPUT_TAG).performTextInput("weiter")
        settleAfterTyping()
        composeRule.onNodeWithTag(STEER_SEND_TAG).performClick()
        assertTrue("steer must reach the requester", steerer.started.await(3, TimeUnit.SECONDS))
        assertEquals(1, steerer.calls.get())

        composeRule.onNodeWithTag(TAG_RUN_STOP).performClick()
        assertTrue("stop must reach the requester", stopper.started.await(3, TimeUnit.SECONDS))
        assertEquals(1, stopper.calls.get())
    }

    @Test
    fun compactRunControlsDisappearWithTheRun() {
        val source = FakeTurnSource()
        enterLandscape(attachment = false, multiline = false, chatSource = source)

        composeRule.onNodeWithText(INPUT_LABEL).performTextInput("Stop-Ende")
        settleAfterTyping()
        composeRule.onNodeWithTag(TAG_COMPOSER_SEND).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        dispatchImeInsets(imeBottom)
        assertCompactRegime()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        dispatchImeInsets(imeBottom)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isNotEmpty()
        }

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TAG_RUN_STOP).fetchSemanticsNodes().isEmpty()
        }
        // The ordinary compact composer comes back.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_ADD).assertIsDisplayed()
        assertTrue(
            "no stale steer control after the run",
            composeRule.onAllNodesWithTag(STEER_INPUT_TAG).fetchSemanticsNodes().isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun enterLandscape(
        attachment: Boolean,
        multiline: Boolean,
        withMessages: Boolean = false,
        chatSource: StreamingChatSource = StreamingChatSource { _, _, _ -> kotlinx.coroutines.flow.emptyFlow() },
        stopRequester: RunStopRequester = NoOpStopRequester(),
        steerRequester: RunSteerRequester = NoOpSteerRequester(),
        attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource
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

        setScreen(
            withMessages = withMessages,
            chatSource = chatSource,
            stopRequester = stopRequester,
            steerRequester = steerRequester,
            attachmentSource = attachmentSource
        )
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
        // Deterministic sync point: wait until the platform reports the real
        // IME inset as gone, instead of a fixed wall-clock sleep. The condition
        // is re-evaluated with a Compose idle round-trip each iteration.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val imeShown = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            imeShown == 0
        }
        composeRule.waitForIdle()
    }

    /**
     * Re-establishes the deterministic landscape+IME scenario after a text
     * input. `performTextInput` focuses the field, which makes the platform
     * open the REAL Gboard; the real IME then redispatches its own (much
     * larger) inset and the layout moves. `performClick` injects a real touch
     * at the node's centre, so a click issued while that asynchronous move is
     * still in flight can land beside the target and never invoke the action.
     * Hiding the real IME and re-applying the measured inset first gives the
     * click a stable geometry again.
     */
    private fun settleAfterTyping() {
        hideRealImeAndWait()
        dispatchImeInsets(imeBottom)
    }

    private fun setScreen(
        withMessages: Boolean,
        chatSource: StreamingChatSource,
        stopRequester: RunStopRequester,
        steerRequester: RunSteerRequester,
        attachmentSource: ChatAttachmentSource
    ) {
        // One controller instance for the whole screen: ChatScreen expects a
        // stable controller, and a new instance per recomposition would lose
        // the run state that RunStarted establishes.
        val controller = RunSessionController(
            chatSource = chatSource,
            serviceStarter = NoOpServiceStarter,
            notificationCoordinator = NoOpNotificationCoordinator
        )
        composeRule.setContent {
            HermesAppSurface {
                HermesTheme {
                    ChatScreen(
                        historySource = MessageHistorySource { _, _ ->
                            MessageHistoryResult.Success(
                                MessagePage(if (withMessages) messages() else emptyList())
                            )
                        },
                        runController = controller,
                        runStopRequester = stopRequester,
                        runSteerRequester = steerRequester,
                        chatDraftRepository = NoOpChatDraftRepository(),
                        attachmentSource = attachmentSource,
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
        assertCompactRegime()
        composeRule.onNodeWithText(HEADER_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(INPUT_LABEL).assertIsDisplayed()

        // The compact chip shows the name and the remove action; the size line
        // and the picker (a turn carries at most one image) are dropped there.
        val labels = mutableListOf(INPUT_LABEL)
        if (ChatAttachmentDraftHolder.get(PROFILE_ID, SESSION_ID) != null) {
            labels += listOf(ATTACHMENT_NAME, REMOVE_LABEL, SEND_LABEL)
        } else {
            labels += listOf(SEND_LABEL, ADD_LABEL)
        }
        labels.forEach { label ->
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label must keep a positive height, was ${bounds.height}",
                bounds.height > 0f
            )
            assertTrue(
                "$label bottom ${bounds.bottom} must stay above the keyboard top ${keyboardTop()}",
                bounds.bottom <= keyboardTop()
            )
        }
    }

    private fun assertNodesAboveKeyboard(vararg tags: String) {
        assertCompactRegime()
        tags.forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag must keep a positive height, was ${bounds.height}",
                bounds.height > 0f
            )
            assertTrue(
                "$tag bottom ${bounds.bottom} must stay above the keyboard top ${keyboardTop()}",
                bounds.bottom <= keyboardTop()
            )
        }
    }

    private fun assertCompactRegime() {
        val availableDp = with(composeRule.activity.resources.displayMetrics) {
            keyboardTop() / density
        }
        assertTrue(
            "the scenario must exercise the compact composer: available " +
                "$availableDp dp vs threshold $COMPACT_COMPOSER_MAX_HEIGHT",
            isCompactRegime()
        )
    }

    private fun keyboardTop(): Float {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        return root.height - imeBottom
    }

    /**
     * The touch target height of one tagged control, in dp.
     *
     * Material buttons draw a 40 dp visual and report their layout bounds as
     * 40 dp, while the platform touch box is expanded to the 48 dp minimum
     * (`minimumInteractiveComponentSize`). [touchBoundsInRoot] is that box -
     * the same one the accessibility tree / uiautomator reports and the same
     * one the platform hit test uses - so it is what has to be measured here.
     */
    private fun touchTargetDp(tag: String): Float {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
        return with(composeRule.activity.resources.displayMetrics) { bounds.height / density }
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
        // `dispatchApplyWindowInsets` is synchronous: it applies the inset to
        // the view tree and drives the Compose relayout read from
        // `WindowInsets.ime`. `waitForIdle` therefore settles on the injected
        // geometry deterministically; the previous fixed `Thread.sleep(300)`
        // settle was removed. (`isCompactRegime` reads the root view height,
        // which the internal IME padding does not shrink, so it is NOT a valid
        // observable for inset consumption and is only used by
        // `assertCompactRegime`.)
        composeRule.waitForIdle()
    }

    private fun isCompactRegime(): Boolean {
        val availableDp = with(composeRule.activity.resources.displayMetrics) {
            keyboardTop() / density
        }
        return availableDp < COMPACT_COMPOSER_MAX_HEIGHT.value
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

    /** Latched streaming fake: events are enqueued and closed by the test. */
    private class FakeTurnSource : StreamingChatSource {
        val opened = CountDownLatch(1)
        @Volatile
        private var activeChannel: Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            activeChannel = channel
            opened.countDown()
            return flow {
                for (event in channel) emit(event)
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            activeChannel?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            activeChannel?.close()
        }
    }

    private class RecordingStopRequester : RunStopRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)

        override suspend fun stopRun(profileId: String, runId: String): RunStopResult {
            calls.incrementAndGet()
            started.countDown()
            return RunStopResult.Accepted("run_synthetic")
        }
    }

    private class RecordingSteerRequester : RunSteerRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)

        override suspend fun steerRun(profileId: String, runId: String, text: String): RunSteerResult {
            calls.incrementAndGet()
            started.countDown()
            return RunSteerResult.Queued("run_synthetic")
        }
    }

    private class NoOpStopRequester : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): RunStopResult =
            RunStopResult.Accepted("run_synthetic")
    }

    private class NoOpSteerRequester : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): RunSteerResult =
            RunSteerResult.Queued("run_synthetic")
    }

    /** Every read reports the closed over-limit outcome, like a 12 MiB pick. */
    private class TooLargeAttachmentSource : ChatAttachmentSource {
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
        ): ChatAttachmentReadResult = ChatAttachmentReadResult.TooLarge
    }

    private object NoOpServiceStarter : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val HEADER_LABEL = "Chat"
        const val INPUT_LABEL = "Nachricht"
        const val SEND_LABEL = "Senden"
        const val ADD_LABEL = "Anhang"
        const val REMOVE_LABEL = "Entfernen"
        const val ATTACHMENT_NAME = "holiday.png"
        const val ATTACHMENT_SIZE = "1.4 MiB"
        const val RUN_ID = "run_fixture_0001"
        const val SIZE_ERROR_LABEL = "Der Anhang ist zu groß. Maximal 5 MiB pro Bild."
        const val STEER_INPUT_TAG = "chatSteerInput"
        const val STEER_SEND_TAG = "chatSteerSend"
        const val MIN_TOUCH_TARGET_DP = 48f
        /** Width floor of the compact draft field with an attachment staged. */
        const val MIN_FIELD_WIDTH_DP = 96f
        /** Height the chat root is left with in the landscape+IME scenario. */
        const val AVAILABLE_DP = 150f
    }
}
