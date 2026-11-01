package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.host.GpuMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsSource
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.ui.components.TAG_WORKING_GLYPH
import io.github.excludebarrier.orsikrelay.ui.monitoring.Freshness
import io.github.excludebarrier.orsikrelay.ui.monitoring.MetricHistory
import io.github.excludebarrier.orsikrelay.ui.monitoring.MonitoringRoute
import io.github.excludebarrier.orsikrelay.ui.monitoring.MonitoringScreen
import io.github.excludebarrier.orsikrelay.ui.monitoring.MonitoringTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The monitoring page: live values from /v1/host/metrics only, local history, honest states. */
class MonitoringScreenUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gib = 1024L * 1024 * 1024
    private val tasks = mutableListOf<Pair<String, String>>()

    private fun metrics(gpu: GpuMetrics? = GpuMetrics("RTX 4070", 38, 5 * gib, 12 * gib)) = HostMetrics(
        cpuPercent = 15.2, cores = 16, memoryUsedBytes = 23 * gib, memoryTotalBytes = 64 * gib, gpu = gpu,
        services = listOf(ServiceState("hermes-gateway", true), ServiceState("backup", false), ServiceState("docker", null))
    )

    private fun show(result: HostMetricsResult?, last: HostMetrics? = (result as? HostMetricsResult.Ready)?.metrics, history: MetricHistory = MetricHistory()) {
        composeRule.setContent {
            HermesTheme {
                MonitoringScreen(
                    serverName = "Heimserver",
                    result = result,
                    last = last,
                    history = history,
                    freshness = if (last != null) Freshness.Seconds(7) else null,
                    refreshing = false,
                    onBack = {},
                    onRefresh = {},
                    onRetry = { retries++ },
                    startTask = { title, prompt -> tasks += title to prompt }
                )
            }
        }
    }

    private var retries = 0

    private fun scrollTo(tag: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun readyValuesShowCpuMemoryGpuAndTheStatus() {
        val m = metrics()
        show(HostMetricsResult.Ready(m), history = MetricHistory().plus(m))

        composeRule.onNodeWithText("Heimserver · ${context.getString(R.string.monitoring_updated_seconds, 7)}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "${context.getString(R.string.home_server_cpu)}, 15 %, ${context.resources.getQuantityString(R.plurals.home_server_cores, 16, 16)}"
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.monitoring_memory_detail, "23.0", "64.0"), substring = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.monitoring_history_hint)).assertIsDisplayed()
        scrollTo(MonitoringTags.GPU)
        composeRule.onNodeWithText("RTX 4070").assertIsDisplayed()
    }

    @Test
    fun withoutAGpuTheCardSaysSo() {
        show(HostMetricsResult.Ready(metrics(gpu = null)))

        scrollTo(MonitoringTags.GPU)
        composeRule.onNodeWithText(context.getString(R.string.monitoring_gpu_none)).assertIsDisplayed()
    }

    @Test
    fun servicesShowTheirStateAndAStoppedOneCanAskHermes() {
        show(HostMetricsResult.Ready(metrics()))

        scrollTo(MonitoringTags.SERVICES)
        composeRule.onNodeWithTag(MonitoringTags.SERVICE_PREFIX + "hermes-gateway").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.monitoring_service_running)).assertExists()
        // Not asked is "unknown", never "stopped".
        composeRule.onNode(hasTestTag(MonitoringTags.SERVICE_PREFIX + "docker"), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.monitoring_service_unknown), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(MonitoringTags.ASK_PREFIX + "backup").performClick()

        assertEquals("backup", tasks.single().first)
        assertTrue(tasks.single().second.contains("journalctl --user -u backup"))
    }

    @Test
    fun aFailedReadKeepsTheLastValuesAndOffersRetry() {
        show(HostMetricsResult.Failed, last = metrics())

        composeRule.onNodeWithTag(MonitoringTags.FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(MonitoringTags.CPU).assertExists()
        composeRule.onNodeWithTag(MonitoringTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun anOlderBridgeGetsTheUnsupportedNote() {
        show(HostMetricsResult.Unsupported, last = null)

        composeRule.onNodeWithTag(MonitoringTags.UNSUPPORTED).assertIsDisplayed()
        composeRule.onNodeWithTag(MonitoringTags.CPU).assertDoesNotExist()
    }

    @Test
    fun theFirstReadShowsTheGlyph() {
        show(null, last = null)

        composeRule.onNodeWithTag(TAG_WORKING_GLYPH).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.monitoring_loading)).assertIsDisplayed()
    }

    @Test
    fun theHermesTasksOpenPreparedChats() {
        show(HostMetricsResult.Ready(metrics()))

        scrollTo(MonitoringTags.TASK_DOCKER)
        composeRule.onNodeWithTag(MonitoringTags.TASK_DOCKER).performClick()
        composeRule.onNodeWithTag(MonitoringTags.TASK_STORAGE).performClick()

        assertEquals(context.getString(R.string.home_task_docker_prompt), tasks[0].second)
        assertEquals(context.getString(R.string.monitoring_task_storage_prompt), tasks[1].second)
    }

    @Test
    fun theRouteReadsFromTheSourceAndShowsTheValues() {
        val reads = AtomicInteger()
        val source = HostMetricsSource { reads.incrementAndGet(); HostMetricsResult.Ready(metrics()) }
        composeRule.setContent {
            HermesTheme {
                MonitoringRoute(profileId = "p", serverName = "Heimserver", source = source, onBack = {}, startTask = { _, _ -> })
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag(MonitoringTags.CPU)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(reads.get() >= 1)
    }
}
