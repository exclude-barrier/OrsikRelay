package io.github.excludebarrier.orsikrelay

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionOutcome
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingFailure
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.SteerRecoveryBlock
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.chat.TurnFailureBlock
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility of the core screens at a large font scale.
 *
 * The three core screens (onboarding pairing form, session list, chat) are
 * rendered with the production composables, the production test tags and the
 * same synthetic fakes the other UI tests use. Each is rendered once with
 * `fontScale = 2.0` (the Android "largest" accessibility font) and once with
 * `fontScale = 1.0` (control), and for the primary action of each screen:
 *
 * - the action is reachable (`performScrollTo()` for the actions that live in
 *   a scrollable container) and displayed;
 * - every clickable element of the action keeps a Material 3 touch target of
 *   at least 48 dp x 48 dp;
 * - the action label is not visually truncated at the layout level.
 *
 * The truncation check uses the `SemanticsActions.GetTextLayoutResult` action
 * (the same text-layout channel an accessibility service reads) and fails when
 * a line is ellipsized (`isLineEllipsized`), i.e. text clipped by `maxLines` +
 * `Ellipsis`; a positive control proves the probe really flags such a label.
 * No production behaviour is asserted here that the screen does not already
 * own; only synthetic values are used.
 *
 * Deliberately out of scope: TalkBack navigation/traversal order, real hardware
 * fonts and the system-wide font-size setting (the scale is injected here).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityLargeFontUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------
    // Onboarding (pairing mode): primary action = submit button.
    // ------------------------------------------------------------------

    @Test
    fun onboardingSubmitIsReachableAndSizedAtLargeFont() {
        renderAt(LARGE_FONT_SCALE) {
            OnboardingRoute(
                connect = {
                    ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT)
                },
                connectPairing = { _, _, _ ->
                    DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
                },
                onConnected = {}
            )
        }
        assertOnboardingSubmit(actionLabel = ONBOARDING_SUBMIT_LABEL, scrollable = true)
    }

    @Test
    fun onboardingSubmitIsReachableAndSizedAtNormalFont() {
        renderAt(NORMAL_FONT_SCALE) {
            OnboardingRoute(
                connect = {
                    ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT)
                },
                connectPairing = { _, _, _ ->
                    DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
                },
                onConnected = {}
            )
        }
        assertOnboardingSubmit(actionLabel = ONBOARDING_SUBMIT_LABEL, scrollable = true)
    }

    private fun assertOnboardingSubmit(actionLabel: String, scrollable: Boolean) {
        val submit = composeRule.onNodeWithTag("onboardingSubmitButton")
        if (scrollable) submit.performScrollTo()
        submit.assertIsDisplayed()
        assertTouchTargetAtLeast48Dp(submit, "onboarding submit")
        assertLabelNotTruncated(actionLabel)
    }

    // ------------------------------------------------------------------
    // Session list: primary action = session entry (and the refresh control).
    // ------------------------------------------------------------------

    @Test
    fun sessionEntryIsReachableAndSizedAtLargeFont() {
        renderSessions(LARGE_FONT_SCALE)
        assertSessionEntry(SESSION_TITLES.last(), scrollable = true)
        assertRefreshControl()
    }

    @Test
    fun sessionEntryIsReachableAndSizedAtNormalFont() {
        renderSessions(NORMAL_FONT_SCALE)
        assertSessionEntry(SESSION_TITLES.last(), scrollable = true)
        assertRefreshControl()
    }

    private fun renderSessions(fontScale: Float) {
        val sessions = SESSION_TITLES.mapIndexed { index, title ->
            HermesSession(
                id = "fixture-$index",
                title = title,
                source = "cli",
                lastActiveEpochSeconds = null,
                messageCount = (index + 1).toLong(),
                pinned = index == 0
            )
        }
        renderAt(fontScale) {
            SessionsScreen(
                pageSource = SessionPageSource { _, _, _ ->
                    SessionListResult.Success(SessionPage(sessions, hasMore = false))
                },
                profileId = "fixture-profile-id"
            )
        }
        waitForText(SESSION_TITLES.first())
    }

    private fun assertSessionEntry(title: String, scrollable: Boolean) {
        if (scrollable) {
            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasText(title) and hasClickAction())
        }
        val entry = composeRule.onNode(hasText(title) and hasClickAction())
        entry.assertIsDisplayed()
        assertTouchTargetAtLeast48Dp(entry, "session entry")
        val meta = sessionMetaFor(title)
        composeRule.onNodeWithText(meta, useUnmergedTree = true).assertIsDisplayed()
        assertLabelNotTruncated(meta)
    }

    private fun sessionMetaFor(title: String): String {
        val count = SESSION_TITLES.indexOf(title) + 1
        return InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(
                R.plurals.session_message_count,
                count,
                count.toString()
            )
    }

    private fun assertRefreshControl() {
        composeRule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.sessions_more_options)
        ).performClick()
        val refresh = composeRule.onNodeWithTag("sessions_menu_refresh")
        refresh.assertIsDisplayed()
        assertTouchTargetAtLeast48Dp(refresh, "sessions refresh")
        assertLabelNotTruncated(REFRESH_LABEL)
        refresh.performClick()
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------
    // Chat: primary action = composer send, with one synthetic message.
    // ------------------------------------------------------------------

    @Test
    fun composerSendIsReachableAndSizedAtLargeFont() {
        renderChat(LARGE_FONT_SCALE)
        assertComposerSend()
    }

    @Test
    fun composerSendIsReachableAndSizedAtNormalFont() {
        renderChat(NORMAL_FONT_SCALE)
        assertComposerSend()
    }

    @Test
    fun chatNoticeCardGrowsInsteadOfClippingAtLargeFont() {
        val title = resources().getString(R.string.error_connection_network)
        val hint = resources().getString(R.string.sessions_error_hint_unreachable)
        val secondary = resources().getString(R.string.chat_turn_failure_refresh_hint)
        renderAt(LARGE_FONT_SCALE) {
            TurnFailureBlock(
                error = ChatStreamError.Network,
                showRefreshHistory = true,
                onRefreshHistory = {}
            )
        }

        // Every text line wraps instead of being ellipsized; the card grows
        // with the content.
        assertLabelNotTruncated(title)
        assertLabelNotTruncated(hint)
        assertLabelNotTruncated(secondary)
        assertLabelNotTruncated(REFRESH_HISTORY_LABEL)

        // The card action keeps the platform touch target even at 2.0.
        assertTouchTargetAtLeast48Dp(
            composeRule.onNodeWithText(REFRESH_HISTORY_LABEL),
            "chat notice action"
        )
    }

    private fun resources() =
        InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun chatSteerNoticeGrowsInsteadOfClippingAtLargeFont() {
        val title = resources().getString(R.string.chat_steer_recovery_title_counter, 1, 3)
        val steerText = "Eine längere Kurskorrektur, die auch bei großer Schrift vollständig lesbar bleiben muss"
        val quoted = resources().getString(R.string.chat_steer_recovery_quote, steerText)
        val action = resources().getString(R.string.chat_steer_send_as_new_message)
        renderAt(LARGE_FONT_SCALE) {
            SteerRecoveryBlock(
                index = 0,
                total = 3,
                text = steerText,
                actionLabel = action,
                clearDraftHint = resources().getString(R.string.chat_steer_recovery_clear_draft_hint),
                enabled = true,
                onSendAsNewMessage = {},
                testTag = "chatSteerRecovery:0",
                actionTestTag = "chatSteerSendAsNewMessage:0"
            )
        }

        assertLabelNotTruncated(title)
        assertLabelNotTruncated(quoted)
        assertLabelNotTruncated(action)
        assertTouchTargetAtLeast48Dp(
            composeRule.onNodeWithText(action),
            "chat steer notice action"
        )
    }

    private fun renderChat(fontScale: Float) {
        renderAt(fontScale) {
            ChatScreen(
                historySource = MessageHistorySource { _, _ ->
                    MessageHistoryResult.Success(
                        MessagePage(
                            listOf(
                                HermesChatMessage(
                                    id = 1L,
                                    role = MessageRole.ASSISTANT,
                                    content = CHAT_MESSAGE,
                                    timestampSeconds = null,
                                    toolName = null
                                )
                            )
                        )
                    )
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
        waitForText(CHAT_MESSAGE)
    }

    private fun assertComposerSend() {
        // The composer is a fixed sibling of the timeline, not part of any
        // scrollable container, so it is proven reachable with
        // assertIsDisplayed() instead of performScrollTo() (which requires a
        // scrollable ancestor). The scrollable timeline itself is exercised by
        // the synthetic message below.
        composeRule.onNodeWithText(CHAT_MESSAGE).performScrollTo().assertIsDisplayed()
        val send = composeRule.onNodeWithTag(TAG_COMPOSER_SEND)
        send.assertIsDisplayed()
        assertTouchTargetAtLeast48Dp(send, "composer send")
        // The send action is an icon button; its accessible label is carried
        // by the content description and cannot be ellipsized.
        send.assertContentDescriptionEquals(SEND_LABEL)
    }

    // ------------------------------------------------------------------
    // Positive control for the layout-level truncation probe.
    // ------------------------------------------------------------------

    @Test
    fun truncationProbeDetectsAnEllipsizedLabel() {
        // A one-line label with an ellipsis overflow in a one-pixel box must
        // be reported as ellipsized, so the probe used for the primary action
        // labels is not a vacuous always-green assertion.
        renderAt(LARGE_FONT_SCALE) {
            Text(
                text = PROBE_LABEL,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(1.dp)
            )
        }
        assertTrue(
            "the truncation probe must flag an ellipsized label",
            isLabelEllipsized(PROBE_LABEL)
        )
    }

    // ------------------------------------------------------------------
    // Shared helpers.
    // ------------------------------------------------------------------

    private fun renderAt(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                HermesTheme { content() }
            }
        }
    }

    /**
     * The touch target (the platform touch box, expanded to the Material 3
     * 48 dp `minimumInteractiveComponentSize`) must stay at least 48 dp wide
     * and tall. `touchBoundsInRoot` is the box the accessibility tree and the
     * hit test use; the visual layout of a Material button is deliberately
     * smaller (40 dp), so the layout bounds are not what has to be measured.
     */
    private fun assertTouchTargetAtLeast48Dp(node: SemanticsNodeInteraction, label: String) {
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val widthDp = with(composeRule.density) { bounds.width / density }
        val heightDp = with(composeRule.density) { bounds.height / density }
        assertTrue(
            "$label touch target width must be >= $MIN_TOUCH_TARGET_DP dp, was $widthDp dp",
            widthDp >= MIN_TOUCH_TARGET_DP
        )
        assertTrue(
            "$label touch target height must be >= $MIN_TOUCH_TARGET_DP dp, was $heightDp dp",
            heightDp >= MIN_TOUCH_TARGET_DP
        )
    }

    /**
     * The action label must not be clipped by the layout (`maxLines` +
     * `Ellipsis`). The rendered [TextLayoutResult] is read through the public
     * semantics action `GetTextLayoutResult`; `isLineEllipsized` is the exact
     * signal that a line no longer fits its `maxLines`/overflow budget.
     *
     * `hasVisualOverflow` is deliberately NOT used: it ORs the width/height
     * flags, and `didOverflowWidth` is already true for an exactly-fitting
     * label because the measured (integer) node width is the floored float
     * line width, so it produces false positives on every ordinary label.
     */
    private fun assertLabelNotTruncated(label: String) {
        assertTrue(
            "'$label' must not be ellipsized/truncated",
            !isLabelEllipsized(label)
        )
    }

    /** True when any rendered line of [label] is ellipsized (layout truncation). */
    private fun isLabelEllipsized(label: String): Boolean {
        val node = composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("no GetTextLayoutResult action exposed for '$label'", action != null)
        val layouts = ArrayList<TextLayoutResult>()
        action!!.action?.invoke(layouts)
        assertTrue("no TextLayoutResult for '$label'", layouts.isNotEmpty())
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
        const val LARGE_FONT_SCALE = 2.0f
        const val NORMAL_FONT_SCALE = 1.0f
        const val MIN_TOUCH_TARGET_DP = 48f
        val ONBOARDING_SUBMIT_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.onboarding_submit) }
        val REFRESH_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.sessions_refresh) }
        val SEND_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_send) }
        val REFRESH_HISTORY_LABEL: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_refresh_history) }
        const val PROBE_LABEL = "Fixture-Probe-Label"
        const val CHAT_MESSAGE = "Fixture-Antwort"
        val SESSION_TITLES = listOf("Fixture Alpha", "Fixture Beta", "Fixture Gamma")
    }
}
