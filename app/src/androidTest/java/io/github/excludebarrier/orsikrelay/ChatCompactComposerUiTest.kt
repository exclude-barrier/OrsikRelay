package io.github.excludebarrier.orsikrelay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_ADD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_BAR
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_FIELD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compact composer in a height-constrained chat root without the emulator
 * keyboard: the production [ChatScreen] is rendered in a fixed-height box
 * below `COMPACT_COMPOSER_MAX_HEIGHT`, so the compact regime is deterministic
 * on every device.
 *
 * It covers what that regime owes the user independent of the IME tests:
 * - the composer is pinned to the bottom edge, not glued directly below the
 *   header (the timeline is not composed there, so the layout must reserve the
 *   remaining space explicitly);
 * - a single draft line is rendered completely at font scale 1.0 and 1.3 (the
 *   old fixed 56 dp field height clipped it as soon as the text grew);
 * - the interactive controls keep their 48 dp touch targets at a raised font
 *   scale.
 */
class ChatCompactComposerUiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerIsPinnedToTheBottomOfTheCompactLayout() {
        render(fontScale = 1.0f)
        waitForText(DRAFT)

        assertComposerEndsAtContainerBottom()
        assertComposerNotGluedToTheHeader()
    }

    @Test
    fun composerIsPinnedToTheBottomAtALargeFontScale() {
        render(fontScale = 1.3f)
        waitForText(DRAFT)

        assertComposerEndsAtContainerBottom()
        assertComposerNotGluedToTheHeader()
    }

    @Test
    fun compactFieldShowsAFullLineAtNormalFontScale() {
        render(fontScale = 1.0f)
        waitForText(DRAFT)

        assertDraftLineFitsTheField()
        assertTouchTargets()
    }

    @Test
    fun compactFieldShowsAFullLineAtLargeFontScale() {
        render(fontScale = 1.3f)
        waitForText(DRAFT)

        assertDraftLineFitsTheField()
        assertTouchTargets()
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private fun assertComposerEndsAtContainerBottom() {
        val containerBottom = composeRule.onNodeWithTag(CONTAINER_TAG)
            .fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = composeRule.onNodeWithTag(TAG_COMPOSER_BAR)
            .fetchSemanticsNode().boundsInRoot.bottom
        val distance = containerBottom - composerBottom
        assertTrue(
            "the composer bottom $composerBottom must end at the container bottom $containerBottom (distance $distance px)",
            distance >= -1f && distance <= 1f
        )
    }

    /**
     * The weighted spacer between header and composer is the only thing that
     * keeps the composer away from the header in the compact regime (the
     * timeline is not composed there). A gap of zero is the defect from the
     * ticket: composer directly below the header.
     */
    private fun assertComposerNotGluedToTheHeader() {
        val headerBottom = composeRule.onNodeWithText(context.getString(R.string.chat_title))
            .fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = composeRule.onNodeWithTag(TAG_COMPOSER_BAR)
            .fetchSemanticsNode().boundsInRoot.top
        val gapDp = with(composeRule.density) { (composerTop - headerBottom) / density }
        assertTrue(
            "the compact composer must not sit directly below the header (gap ${gapDp}dp)",
            gapDp >= MIN_HEADER_GAP_DP
        )
    }

    /**
     * The draft field must be tall enough for its single visible line plus the
     * Material outlined text field vertical content padding. The line height is
     * read from the rendered [TextLayoutResult] (font scale included), so a
     * fixed-height box that clips the text fails here.
     */
    private fun assertDraftLineFitsTheField() {
        val field = composeRule.onNodeWithTag(TAG_COMPOSER_FIELD, useUnmergedTree = true)
            .fetchSemanticsNode()
        val action = field.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("no GetTextLayoutResult action on the compact draft field", action != null)
        val layouts = ArrayList<TextLayoutResult>()
        action!!.action?.invoke(layouts)
        assertTrue("no TextLayoutResult for the compact draft field", layouts.isNotEmpty())
        val layout = layouts.last()
        assertTrue("the draft must render at least one line", layout.lineCount >= 1)
        val lineHeightPx = layout.getLineBottom(0) - layout.getLineTop(0)
        val contentPaddingPx = with(composeRule.density) { FIELD_VERTICAL_CONTENT_PADDING.toPx() }
        val requiredPx = lineHeightPx + contentPaddingPx
        val fieldHeightPx = field.boundsInRoot.height
        assertTrue(
            "the compact field height ${fieldHeightPx}px must fit the text line ${lineHeightPx}px " +
                "plus the vertical content padding ${contentPaddingPx}px (required ${requiredPx}px)",
            fieldHeightPx + 1f >= requiredPx
        )
    }

    private fun assertTouchTargets() {
        listOf(TAG_ATTACHMENT_ADD, TAG_COMPOSER_SEND).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            val heightDp = with(composeRule.density) { bounds.height / density }
            val widthDp = with(composeRule.density) { bounds.width / density }
            assertTrue(
                "$tag touch target must stay at least $MIN_TOUCH_TARGET_DP dp, was ${widthDp}x${heightDp} dp",
                widthDp >= MIN_TOUCH_TARGET_DP && heightDp >= MIN_TOUCH_TARGET_DP
            )
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun render(fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                HermesTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
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
                            chatDraftRepository = FixedDraftRepository(DRAFT),
                            profileId = PROFILE_ID,
                            sessionId = SESSION_ID,
                            onBack = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CONTAINER_HEIGHT)
                                .testTag(CONTAINER_TAG)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /** Restores one fixed draft without touching the real IME. */
    private class FixedDraftRepository(private val text: String) : ChatDraftRepository {
        override suspend fun loadDraft(profileId: String, sessionId: String): String = text
        override fun persistDraft(profileId: String, sessionId: String, text: String) {}
        override fun clearDraft(profileId: String, sessionId: String) {}
        override fun flushPendingDraft() {}
        override fun clearProfile(profileId: String) {}
    }

    private val noOpServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val runStopRequesterThatIsNeverReached = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in layout tests")
        }
    }

    private val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing {
            throw AssertionError("run-steer must not be reachable in layout tests")
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val CONTAINER_TAG = "compact_chat_container"
        const val DRAFT = "Kurzer Entwurf"
        const val MIN_TOUCH_TARGET_DP = 48f
        const val MIN_HEADER_GAP_DP = 8f
        const val FIELD_VERTICAL_CONTENT_PADDING_DP = 32f
        val FIELD_VERTICAL_CONTENT_PADDING = FIELD_VERTICAL_CONTENT_PADDING_DP.dp
        val CONTAINER_HEIGHT = 280.dp
    }
}
