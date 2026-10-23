package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertTextEquals
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import org.junit.Assert.assertTrue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import io.github.excludebarrier.orsikrelay.ui.components.TAG_LOADING_INDICATOR
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
        // The connection is stated once, in the server card (no second
        // "Erreichbar" chip over the server values).
        assertEquals(0, composeRule.onAllNodesWithText(context.getString(R.string.home_server_reachable)).fetchSemanticsNodes().size)
        scrollTo(HomeTags.QUICK_CHAT)
        composeRule.onNodeWithTag(HomeTags.QUICK_CHAT).assertIsEnabled().assertHasClickAction()
        // Every quick tile works: Server opens the Server tab, the tool tiles
        // start a chat with a prepared request.
        composeRule.onNodeWithTag(HomeTags.QUICK_TILE_PREFIX + "server").assertIsEnabled().assertHasClickAction()
        listOf("terminal", "files", "docker", "projects").forEach { key ->
            scrollTo(HomeTags.QUICK_TILE_PREFIX + key)
            composeRule.onNodeWithTag(HomeTags.QUICK_TILE_PREFIX + key).assertIsEnabled().assertHasClickAction()
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
    fun liveServerValuesFillTheCardAndAStoppedServiceIsNamed() {
        val gib = 1024L * 1024 * 1024
        show(
            connected().copy(
                hostMetrics = HostMetricsResult.Ready(
                    HostMetrics(
                        cpuPercent = 27.6, cores = 6, memoryUsedBytes = 15 * gib, memoryTotalBytes = 30 * gib, gpu = null,
                        services = listOf(ServiceState("hermes-gateway", true), ServiceState("orsik-mobile-bridge", false))
                    )
                )
            )
        )

        composeRule.onNodeWithTag(HomeTags.SERVER_STRIP).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("${context.getString(R.string.home_server_cpu)}, 28 %, " +
            context.resources.getQuantityString(R.plurals.home_server_cores, 6, 6)).assertExists()
        composeRule.onNodeWithContentDescription("${context.getString(R.string.home_server_ram)}, 50 %, 15/30 GB").assertExists()
        composeRule.onNodeWithContentDescription(
            "${context.getString(R.string.home_server_gpu)}, ${context.getString(R.string.home_server_gpu_none)}"
        ).assertExists()
        composeRule.onNodeWithContentDescription("${context.getString(R.string.home_server_services)}, 1/2").assertExists()
        composeRule.onNodeWithTag(HomeTags.SERVER_SERVICES_DOWN)
            .assertTextEquals(context.getString(R.string.home_server_services_down, "orsik-mobile-bridge"))
        assertEquals(0, composeRule.onAllNodesWithText(context.getString(R.string.home_server_preview_text)).fetchSemanticsNodes().size)
    }

    @Test
    fun aServiceTheBridgeCouldNotAskAboutIsNotReportedAsStopped() {
        show(
            connected().copy(
                hostMetrics = HostMetricsResult.Ready(
                    HostMetrics(10.0, 2, 1, 2, null, listOf(ServiceState("hermes-gateway", true), ServiceState("docker", null)))
                )
            )
        )
        composeRule.onNodeWithContentDescription("${context.getString(R.string.home_server_services)}, 1/2").assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag(HomeTags.SERVER_SERVICES_DOWN).fetchSemanticsNodes().size)
    }

    @Test
    fun beforeTheFirstReadThereIsNoLaterVersionClaim() {
        show(connected())
        assertEquals(0, composeRule.onAllNodesWithText(context.getString(R.string.home_server_preview_text)).fetchSemanticsNodes().size)
    }

    @Test
    fun atLargeFontsTheServerValuesStackTwoPerRow() {
        val gib = 1024L * 1024 * 1024
        show(
            connected().copy(
                hostMetrics = HostMetricsResult.Ready(
                    HostMetrics(23.0, 6, 12 * gib, 32 * gib, null, listOf(ServiceState("hermes-gateway", true)))
                )
            ),
            fontScale = 2f
        )
        val cpu = composeRule.onNodeWithContentDescription(
            "${context.getString(R.string.home_server_cpu)}, 23 %, " + context.resources.getQuantityString(R.plurals.home_server_cores, 6, 6)
        ).fetchSemanticsNode().positionInRoot
        val gpu = composeRule.onNodeWithContentDescription(
            "${context.getString(R.string.home_server_gpu)}, ${context.getString(R.string.home_server_gpu_none)}"
        ).fetchSemanticsNode().positionInRoot
        assertTrue("GPU must sit in the second row at 2x font", gpu.y > cpu.y)
    }

    @Test
    fun anUnreadableOrUnsupportedServerKeepsThePlaceholders() {
        show(connected().copy(hostMetrics = HostMetricsResult.Failed))
        composeRule.onNodeWithText(context.getString(R.string.home_server_unreadable)).assertExists()
        composeRule.onNodeWithContentDescription(
            "${context.getString(R.string.home_server_cpu)}, ${context.getString(R.string.home_server_no_value)}"
        ).assertExists()
    }

    @Test
    fun anOlderBridgeShowsThePreviewNote() {
        show(connected().copy(hostMetrics = HostMetricsResult.Unsupported))
        composeRule.onNodeWithText(context.getString(R.string.home_server_preview_text)).assertExists()
    }

    @Test
    fun theAgentSheetOffersOnlyHermes() {
        show(connected())

        composeRule.onNodeWithTag(HomeTags.COMPOSER_AGENT).performClick()

        composeRule.onNodeWithTag(HomeTags.PICKER_HERMES).assertIsDisplayed().assertIsEnabled()
        listOf("auto", "claude_code", "codex", "omp").forEach { key ->
            composeRule.onNodeWithTag(HomeTags.PICKER_PREVIEW_PREFIX + key).assertIsNotEnabled()
        }
        // Same names as the Agents filter.
        listOf("Claude Code", "Codex", "OMP").forEach { name ->
            composeRule.onNodeWithText(name).assertIsDisplayed()
        }
        assertEquals(0, composeRule.onAllNodesWithText("OpenAI Codex").fetchSemanticsNodes().size)
    }

    @Test
    fun largeFontsKeepTheHeaderAndTheDockUsable() {
        show(connected(), fontScale = 2f)

        composeRule.onNodeWithText("hp-server").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).assertIsDisplayed()
    }

    /** Enough recent sessions that the list always scrolls, on any display. */
    private fun connectedLongList() = connected().copy(
        recent = HomeRecent.Ready((1..12).map { HermesSession("r$it", "Fixture $it", null, null, it.toLong(), false) })
    )

    private fun scrollToTheEnd() {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(hasText("Fixture 12"))
    }

    @Test
    fun suggestionsScrollWithTheContentAndNotWithTheDock() {
        show(connectedLongList())

        val chip = hasTestTag(HomeTags.SUGGESTION_PREFIX + "storage")
        composeRule.onNode(chip and hasAnyAncestor(hasTestTag(HomeTags.LIST))).assertIsDisplayed()
        composeRule.onAllNodes(chip and hasAnyAncestor(hasTestTag(HomeTags.DOCK))).assertCountEquals(0)

        scrollToTheEnd()

        composeRule.onAllNodesWithTag(HomeTags.SUGGESTIONS).assertCountEquals(0)
        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).assertIsDisplayed()
    }

    @Test
    fun theHeaderCollapsesIntoACompactBarWhileScrolling() {
        show(connectedLongList())

        composeRule.onAllNodesWithTag(HomeTags.COMPACT_HEADER).assertCountEquals(0)
        scrollToTheEnd()

        composeRule.onNodeWithTag(HomeTags.COMPACT_HEADER).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.COMPACT_SETTINGS).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun aRunningTaskShowsTheLoadingStar() {
        show(connected(runs = listOf(HomeRunItem("s7", "Check backup", awaitingApproval = false, uncertain = false))))

        composeRule.onNode(
            hasTestTag(TAG_LOADING_INDICATOR) and hasAnyAncestor(hasTestTag(HomeTags.RUN_ROW)),
            useUnmergedTree = true
        ).assertExists()
    }

    @Test
    fun atLargeFontsTheApprovalButtonSitsBelowItsText() {
        show(connected(runs = listOf(HomeRunItem("s9", "Docker updates", awaitingApproval = true, uncertain = false))), fontScale = 2f)

        // Unclipped positions: at 2x font the card may reach below the fold.
        val titleNode = composeRule.onNode(
            hasText(context.getString(R.string.home_approval_title)) and hasAnyAncestor(hasTestTag(HomeTags.APPROVAL)),
            useUnmergedTree = true
        ).fetchSemanticsNode()
        val titleBottom = titleNode.positionInRoot.y + titleNode.size.height
        val buttonTop = composeRule.onNodeWithTag(HomeTags.APPROVAL_REVIEW).fetchSemanticsNode().positionInRoot.y
        assertTrue("button top $buttonTop must be below the title bottom $titleBottom", buttonTop >= titleBottom)
        assertNoMidWordBreak(context.getString(R.string.home_approval_title))
    }

    /** Wrapping at a space is fine; a line may only end inside a word at the very end. */
    private fun assertNoMidWordBreak(text: String) {
        val node = composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(HomeTags.APPROVAL)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.first()
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("'$text' breaks mid-word after '${text.substring(0, end)}'", text[end - 1] == ' ' || text[end] == ' ')
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(hasTestTag(tag))
    }
}
