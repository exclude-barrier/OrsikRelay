package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_RESOLVE_LOST
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.ui.chat.RunUncertainBlock
import io.github.excludebarrier.orsikrelay.ui.chat.SteerRecoveryBlock
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_RECONCILE
import io.github.excludebarrier.orsikrelay.ui.chat.TurnFailureBlock
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the shared chat notice card: title, body text, secondary
 * text and actions of the failure and recovery blocks. Synthetic states only,
 * no network.
 */
class ChatNoticeUiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun turnFailureWithNetworkShowsTitleHintAndRefreshAction() {
        var refreshes = 0
        render {
            TurnFailureBlock(
                error = ChatStreamError.Network,
                showRefreshHistory = true,
                onRefreshHistory = { refreshes++ }
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.error_connection_network))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_error_hint_unreachable))
            .assertIsDisplayed()
        assertEquals(
            "notice title and text must use the same type size",
            layoutOf(context.getString(R.string.error_connection_network)).layoutInput.style.fontSize,
            layoutOf(context.getString(R.string.sessions_error_hint_unreachable))
                .layoutInput.style.fontSize
        )
        composeRule.onNodeWithText(context.getString(R.string.chat_turn_failure_refresh_hint))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_refresh_history))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun turnFailureWithTlsShowsTheTlsHint() {
        render {
            TurnFailureBlock(
                error = ChatStreamError.Tls,
                showRefreshHistory = false,
                onRefreshHistory = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.error_connection_tls))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_error_hint_tls))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_turn_failure_draft_kept))
            .assertIsDisplayed()
    }

    @Test
    fun turnFailureWithHttpShowsNoHint() {
        render {
            TurnFailureBlock(
                error = ChatStreamError.Http,
                showRefreshHistory = false,
                onRefreshHistory = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.error_connection_http))
            .assertIsDisplayed()
        listOf(
            R.string.sessions_error_hint_unreachable,
            R.string.sessions_error_hint_tls,
            R.string.sessions_error_hint_credentials,
            R.string.sessions_error_hint_rate_limited,
            R.string.sessions_error_hint_incompatible_api
        ).forEach { hint ->
            assertTrue(
                "HTTP failure must not show the session-list hint $hint",
                composeRule.onAllNodesWithText(context.getString(hint))
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun turnFailureWithoutConfirmedStartOmitsTheRefreshHint() {
        render {
            TurnFailureBlock(
                error = ChatStreamError.Network,
                showRefreshHistory = false,
                onRefreshHistory = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_turn_failure_draft_kept))
            .assertIsDisplayed()
        assertTrue(
            "the refresh hint belongs to the refresh action",
            composeRule
                .onAllNodesWithText(context.getString(R.string.chat_turn_failure_refresh_hint))
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "without a confirmed start there is no refresh action",
            composeRule
                .onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun runUncertainDefaultShowsTitleAndHonestText() {
        render {
            RunUncertainBlock(
                inFlight = false,
                canReconcile = true,
                stillActive = false,
                unavailable = false,
                onReconcile = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RUN_RECONCILE)
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun aLostRunOffersOnlyTheExplicitCloseAction() {
        var resolves = 0
        var reconciles = 0
        render {
            RunUncertainBlock(
                inFlight = false,
                canReconcile = true,
                stillActive = false,
                unavailable = false,
                onReconcile = { reconciles++ },
                lost = true,
                resolving = false,
                onResolve = { resolves++ }
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_run_lost)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(TAG_RUN_RECONCILE).assertCountEquals(0)
        composeRule.onNodeWithTag(TAG_RUN_RESOLVE_LOST)
            .assertTextEquals(context.getString(R.string.chat_run_lost_resolve))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, resolves)
            assertEquals(0, reconciles)
        }
    }

    @Test
    fun runUncertainStillActiveShowsTheServerText() {
        render {
            RunUncertainBlock(
                inFlight = false,
                canReconcile = true,
                stillActive = true,
                unavailable = false,
                onReconcile = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_still_active))
            .assertIsDisplayed()
    }

    @Test
    fun runUncertainUnavailableShowsTheFailureText() {
        render {
            RunUncertainBlock(
                inFlight = false,
                canReconcile = true,
                stillActive = false,
                unavailable = true,
                onReconcile = {}
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_unavailable))
            .assertIsDisplayed()
    }

    @Test
    fun runUncertainInFlightDisablesTheCheckAction() {
        var reconciles = 0
        render {
            RunUncertainBlock(
                inFlight = true,
                canReconcile = true,
                stillActive = false,
                unavailable = false,
                onReconcile = { reconciles++ }
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_run_uncertain_checking))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RUN_RECONCILE)
            .assertIsNotEnabled()
            .performClick()
        assertEquals("an in-flight check must never start a second request", 0, reconciles)
    }

    @Test
    fun steerRecoverySingleItemHasNoCounterAndQuotesTheText() {
        render {
            SteerRecoveryBlock(
                index = 0,
                total = 1,
                text = "Kurs nach links",
                actionLabel = context.getString(R.string.chat_steer_send_as_new_message),
                clearDraftHint = context.getString(R.string.chat_steer_recovery_clear_draft_hint),
                enabled = true,
                onSendAsNewMessage = {},
                testTag = "chatSteerRecovery:0",
                actionTestTag = "chatSteerSendAsNewMessage:0"
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.chat_steer_recovery_quote, "Kurs nach links"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0")
            .assertIsEnabled()
    }

    @Test
    fun steerRecoveryThreeItemsShowTheCounter() {
        render {
            Column {
                repeat(3) { index ->
                    SteerRecoveryBlock(
                        index = index,
                        total = 3,
                        text = "Kurs ${index + 1}",
                        actionLabel = context.getString(R.string.chat_steer_send_as_new_message),
                        clearDraftHint = context.getString(R.string.chat_steer_recovery_clear_draft_hint),
                        enabled = true,
                        onSendAsNewMessage = {},
                        testTag = "chatSteerRecovery:$index",
                        actionTestTag = "chatSteerSendAsNewMessage:$index"
                    )
                }
            }
        }

        repeat(3) { index ->
            composeRule
                .onNodeWithText(
                    context.getString(R.string.chat_steer_recovery_title_counter, index + 1, 3)
                )
                .assertIsDisplayed()
        }
    }

    @Test
    fun steerRecoveryDisabledShowsTheClearDraftHintAndADisabledAction() {
        render {
            SteerRecoveryBlock(
                index = 0,
                total = 1,
                text = "Kurs",
                actionLabel = context.getString(R.string.chat_steer_send_as_new_message),
                clearDraftHint = context.getString(R.string.chat_steer_recovery_clear_draft_hint),
                enabled = false,
                onSendAsNewMessage = {},
                testTag = "chatSteerRecovery:0",
                actionTestTag = "chatSteerSendAsNewMessage:0"
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_clear_draft_hint))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0")
            .assertIsNotEnabled()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = ArrayList<TextLayoutResult>()
        composeRule.onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            HermesTheme { content() }
        }
    }
}
