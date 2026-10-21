package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.ui.home.HomeConnection
import io.github.excludebarrier.orsikrelay.ui.home.HomeRecent
import io.github.excludebarrier.orsikrelay.ui.home.HomeRunItem
import io.github.excludebarrier.orsikrelay.ui.home.HomeScreen
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import io.github.excludebarrier.orsikrelay.ui.home.HomeUiState
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The stateless Home screen in its connection states. */
class HomeScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private val openedRuns = mutableListOf<String>()
    private val drafts = mutableListOf<String>()
    private var retries = 0
    private var switches = 0

    private fun show(state: HomeUiState, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesTheme {
                    HomeScreen(
                        state = state,
                        onDraftChange = { drafts += it },
                        onSend = {},
                        onAttach = {},
                        onRemoveAttachment = {},
                        onOpenRun = { openedRuns += it },
                        onOpenSession = {},
                        onOpenAllSessions = {},
                        onSwitchServer = { switches += 1 },
                        onOpenSettings = {},
                        onRetry = { retries += 1 }
                    )
                }
            }
        }
    }

    private fun connected(runs: List<HomeRunItem> = emptyList()) = HomeUiState(
        serverName = "hp-server",
        connection = HomeConnection.Connected,
        runs = runs,
        recent = HomeRecent.Ready(
            listOf(
                HermesSession("s1", "Weekly planning", null, null, 34, true),
                HermesSession("s2", "Check backup", "cron", null, 3, false)
            )
        )
    )

    @Test
    fun connectedShowsHeaderServerQuickAccessAndRecent() {
        show(connected())

        composeRule.onNodeWithText("hp-server").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_status_connected)).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.SERVER_STRIP).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_server_reachable)).assertIsDisplayed()
        scrollTo(HomeTags.QUICK_CHAT)
        composeRule.onNodeWithTag(HomeTags.QUICK_CHAT).assertIsEnabled()
        listOf("server", "terminal", "files", "docker", "projects").forEach { key ->
            scrollTo(HomeTags.QUICK_TILE_PREFIX + key)
            composeRule.onNodeWithTag(HomeTags.QUICK_TILE_PREFIX + key).assertIsNotEnabled()
        }
        scrollTo(HomeTags.RECENT_ROW)
        assertEquals(2, composeRule.onAllNodesWithTag(HomeTags.RECENT_ROW).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsEnabled()
        // An empty draft cannot be sent.
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).assertIsNotEnabled()
    }

    @Test
    fun aSuggestionOnlyFillsTheInput() {
        show(connected())

        composeRule.onNodeWithTag(HomeTags.SUGGESTION_PREFIX + "storage").performClick()

        assertEquals(listOf(context.getString(R.string.home_suggestion_storage)), drafts)
    }

    @Test
    fun checkingShowsSkeletonsAndDisablesTheDock() {
        show(HomeUiState(serverName = "hp-server", connection = HomeConnection.Checking))

        // The dock's hint says the same, so the header line is matched by its tag.
        composeRule.onNode(
            hasText(context.getString(R.string.home_status_checking)) and hasAnyAncestor(hasTestTag(HomeTags.STATUS))
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.SKELETON).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsNotEnabled()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).assertIsNotEnabled()
    }

    @Test
    fun offlineOffersRetryAndServerSwitchAndDisablesTheDock() {
        show(
            HomeUiState(
                serverName = "hp-server",
                connection = HomeConnection.Unreachable(ConnectionFailureReason.TIMEOUT)
            )
        )

        composeRule.onNodeWithText(context.getString(R.string.home_status_unreachable)).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.OFFLINE_RETRY).performClick()
        composeRule.onNodeWithTag(HomeTags.OFFLINE_SWITCH).performClick()
        assertEquals(1, retries)
        assertEquals(1, switches)
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsNotEnabled()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).assertIsNotEnabled()
    }

    @Test
    fun anApprovalIsOnlyReviewedAndOpensItsChat() {
        show(connected(runs = listOf(HomeRunItem("s9", "Docker updates", awaitingApproval = true, uncertain = false))))

        composeRule.onNodeWithTag(HomeTags.APPROVAL).assertIsDisplayed()
        // Home never decides an approval: there is no allow or deny action.
        composeRule.onNodeWithText(context.getString(R.string.approval_allow)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.approval_deny)).assertDoesNotExist()
        composeRule.onNodeWithTag(HomeTags.APPROVAL_REVIEW).performClick()

        assertEquals(listOf("s9"), openedRuns)
    }

    @Test
    fun aRunningTaskOpensItsChat() {
        show(connected(runs = listOf(HomeRunItem("s7", "Check backup", awaitingApproval = false, uncertain = false))))

        composeRule.onNodeWithTag(HomeTags.RUN_ROW).performClick()

        assertEquals(listOf("s7"), openedRuns)
    }

    @Test
    fun theAgentSheetOffersOnlyHermes() {
        show(connected())

        composeRule.onNodeWithTag(HomeTags.COMPOSER_AGENT).performClick()

        composeRule.onNodeWithTag(HomeTags.PICKER_HERMES).assertIsDisplayed().assertIsEnabled()
        listOf("auto", "claude_code", "codex", "omp").forEach { key ->
            composeRule.onNodeWithTag(HomeTags.PICKER_PREVIEW_PREFIX + key).assertIsNotEnabled()
        }
    }

    @Test
    fun largeFontsKeepTheHeaderAndTheDockUsable() {
        show(connected(), fontScale = 2f)

        composeRule.onNodeWithText("hp-server").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).assertIsDisplayed()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(hasTestTag(tag))
    }
}
