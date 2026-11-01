package io.github.excludebarrier.orsikrelay.ui.monitoring

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsSource
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.ui.common.criticalContainerColor
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.PreviewNote
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object MonitoringTags {
    const val SCREEN = "monitoring_screen"
    const val BACK = "monitoring_back"
    const val REFRESH = "monitoring_refresh"
    const val STATUS = "monitoring_status"
    const val CPU = "monitoring_cpu"
    const val MEMORY = "monitoring_memory"
    const val GPU = "monitoring_gpu"
    const val SERVICES = "monitoring_services"
    const val SERVICE_PREFIX = "monitoring_service_"
    const val ASK_PREFIX = "monitoring_ask_"
    const val FAILED = "monitoring_failed"
    const val RETRY = "monitoring_retry"
    const val UNSUPPORTED = "monitoring_unsupported"
    const val HISTORY_HINT = "monitoring_history_hint"
    const val TASK_STORAGE = "monitoring_task_storage"
    const val TASK_DOCKER = "monitoring_task_docker"
}

/** The page polls while it is visible; Home keeps its slower 30 s pace. */
internal const val MONITORING_INTERVAL_MS = 5_000L

/**
 * Live values of the server host, read only from `GET /v1/host/metrics`
 * every [MONITORING_INTERVAL_MS] while the page is visible. The small bars
 * are a local history since the page opened; nothing older is invented.
 * [startTask] opens a new chat with a prepared request in its field (the user
 * still sends it).
 */
@Composable
internal fun MonitoringRoute(
    profileId: String,
    serverName: String?,
    source: HostMetricsSource,
    onBack: () -> Unit,
    startTask: (title: String, prompt: String) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var last by remember(profileId) { mutableStateOf<HostMetrics?>(null) }
    var result by remember(profileId) { mutableStateOf<HostMetricsResult?>(null) }
    var history by remember(profileId) { mutableStateOf(MetricHistory()) }
    var updatedAt by remember(profileId) { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }

    suspend fun readOnce() {
        val read = try {
            withContext(ioDispatcher) { source.read(profileId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HostMetricsResult.Failed
        }
        result = read
        if (read is HostMetricsResult.Ready) {
            last = read.metrics
            history = history.plus(read.metrics)
            updatedAt = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(profileId, source, retries) {
        if (profileId.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                readOnce()
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    MonitoringScreen(
        serverName = serverName,
        result = result,
        last = last,
        history = history,
        freshness = if (updatedAt == 0L) null else freshness(updatedAt, now),
        refreshing = refreshing,
        onBack = onBack,
        onRefresh = {
            if (!refreshing) scope.launch {
                refreshing = true
                try { readOnce() } finally { refreshing = false }
            }
        },
        onRetry = { retries++ },
        startTask = startTask
    )
}

@Composable
internal fun MonitoringScreen(
    serverName: String?,
    result: HostMetricsResult?,
    last: HostMetrics?,
    history: MetricHistory,
    freshness: Freshness?,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    startTask: (title: String, prompt: String) -> Unit
) {
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val title = stringResource(R.string.monitoring_title)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(MonitoringTags.SCREEN)
    ) {
        // The bar keeps the page background; its title and divider appear
        // once the large title has scrolled away.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(MonitoringTags.BACK)) {
                Icon(OrsikIcons.ArrowBack, contentDescription = stringResource(R.string.chat_back))
            }
            Text(
                text = if (scrolled) title else "",
                style = OrsikType.listTitle,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.testTag(MonitoringTags.REFRESH)) {
                if (refreshing) {
                    WorkingGlyph(size = 20.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.monitoring_refresh))
                }
            }
        }
        if (scrolled) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "title") {
                Column {
                    Text(title, style = OrsikType.screenTitle, modifier = Modifier.semantics { heading() })
                    StatusLine(serverName, freshness, result)
                }
            }
            when {
                result == null && last == null -> item(key = "loading") { Loading() }
                result == HostMetricsResult.Unsupported -> item(key = "unsupported") { Unsupported() }
                else -> {
                    if (result == HostMetricsResult.Failed) item(key = "failed") { FailedCard(onRetry) }
                    val shown = last
                    if (shown != null) {
                        // Old values stay, dimmed, while the reads fail.
                        val dim = if (result == HostMetricsResult.Failed) 0.5f else 1f
                        if (history.size <= 1) item(key = "hint") { HistoryHint() }
                        item(key = "cpu") { CpuCard(shown, history.cpu, Modifier.alpha(dim)) }
                        item(key = "memory") { MemoryCard(shown, history.ram, Modifier.alpha(dim)) }
                        item(key = "gpu") { GpuCard(shown, history.gpu, Modifier.alpha(dim)) }
                        item(key = "services") { ServicesCard(shown.services, startTask, Modifier.alpha(dim)) }
                    }
                }
            }
            item(key = "hermes") { HermesTasks(startTask) }
            item(key = "soon") { PreviewNote(stringResource(R.string.monitoring_soon), Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun StatusLine(serverName: String?, freshness: Freshness?, result: HostMetricsResult?) {
    val ok = result is HostMetricsResult.Ready
    val text = listOfNotNull(
        serverName?.takeIf { it.isNotBlank() },
        freshness?.let { freshnessText(it) }
    ).joinToString(" · ")
    if (text.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 4.dp, bottom = 4.dp)
            .testTag(MonitoringTags.STATUS)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) OrsikTheme.statusColors.ok else MaterialTheme.colorScheme.outline)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun freshnessText(freshness: Freshness): String = when (freshness) {
    Freshness.Now -> stringResource(R.string.monitoring_updated_now)
    is Freshness.Seconds -> stringResource(R.string.monitoring_updated_seconds, freshness.value.toInt())
    is Freshness.Minutes -> stringResource(R.string.monitoring_updated_minutes, freshness.value.toInt())
}

@Composable
private fun Loading() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
        WorkingGlyph(size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.monitoring_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Unsupported() {
    Text(
        text = stringResource(R.string.monitoring_unsupported),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp)
            .testTag(MonitoringTags.UNSUPPORTED)
    )
}

@Composable
private fun FailedCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(criticalContainerColor())
            .padding(16.dp)
            .testTag(MonitoringTags.FAILED)
    ) {
        Text(stringResource(R.string.monitoring_failed), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.monitoring_failed_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 10.dp).testTag(MonitoringTags.RETRY)) {
            Text(stringResource(R.string.monitoring_retry))
        }
    }
}

@Composable
private fun HistoryHint() {
    Text(
        stringResource(R.string.monitoring_history_hint),
        style = OrsikType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(MonitoringTags.HISTORY_HINT)
    )
}

@Composable
private fun MetricCard(modifier: Modifier, tag: String, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp)
            .testTag(tag)
    ) { content() }
}

@Composable
private fun CpuCard(metrics: HostMetrics, history: List<Float?>, modifier: Modifier) {
    val cpu = metrics.cpuPercent
    val label = stringResource(R.string.home_server_cpu)
    val value = cpu?.let { "${it.roundToInt()} %" } ?: stringResource(R.string.home_server_no_value)
    val cores = metrics.cores?.let { pluralStringResource(R.plurals.home_server_cores, it, it) }
    MetricCard(modifier, MonitoringTags.CPU) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(label, value, cores).joinToString(", ") }
        ) {
            Ring(fill = cpu?.let { (it / 100).toFloat() }, size = 88.dp)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(label, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                if (cores != null) Text(cores, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HistoryBars(history)
    }
}

@Composable
private fun MemoryCard(metrics: HostMetrics, history: List<Float?>, modifier: Modifier) {
    val fill = memoryFill(metrics)
    val locale = LocalConfiguration.current.locales[0]
    MetricCard(modifier, MonitoringTags.MEMORY) {
        ValueRow(stringResource(R.string.monitoring_memory), fill?.let { "${(it * 100).roundToInt()} %" }, big = true)
        Bar(fill)
        val used = metrics.memoryUsedBytes
        val total = metrics.memoryTotalBytes
        if (used != null && total != null && total > 0) {
            Text(
                stringResource(R.string.monitoring_memory_detail, gigabytes(used, locale), gigabytes(total, locale)),
                style = OrsikType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        HistoryBars(history)
    }
}

@Composable
private fun GpuCard(metrics: HostMetrics, history: List<Float?>, modifier: Modifier) {
    val gpu = metrics.gpu
    val locale = LocalConfiguration.current.locales[0]
    MetricCard(modifier, MonitoringTags.GPU) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_server_gpu), style = OrsikType.listTitle, modifier = Modifier.weight(1f))
            Text(
                gpu?.name ?: stringResource(R.string.monitoring_gpu_none),
                style = OrsikType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (gpu != null) {
            Spacer(Modifier.height(10.dp))
            ValueRow(stringResource(R.string.monitoring_gpu_load), "${gpu.utilizationPercent} %")
            Bar(gpu.utilizationPercent / 100f)
            Spacer(Modifier.height(10.dp))
            val vramFill = if (gpu.memoryTotalBytes > 0) gpu.memoryUsedBytes.toFloat() / gpu.memoryTotalBytes else null
            ValueRow(
                stringResource(R.string.monitoring_vram),
                stringResource(
                    R.string.monitoring_memory_detail,
                    gigabytes(gpu.memoryUsedBytes, locale),
                    gigabytes(gpu.memoryTotalBytes, locale)
                )
            )
            Bar(vramFill)
            HistoryBars(history)
        }
    }
}

@Composable
private fun ServicesCard(services: List<ServiceState>, startTask: (String, String) -> Unit, modifier: Modifier) {
    val title = stringResource(R.string.home_server_services)
    MetricCard(modifier, MonitoringTags.SERVICES) {
        Text(title, style = OrsikType.listTitle, modifier = Modifier.padding(bottom = 6.dp))
        services.forEach { service ->
            val (label, color) = when (service.active) {
                true -> stringResource(R.string.monitoring_service_running) to OrsikTheme.statusColors.ok
                false -> stringResource(R.string.monitoring_service_stopped) to MaterialTheme.colorScheme.error
                null -> stringResource(R.string.monitoring_service_unknown) to MaterialTheme.colorScheme.outline
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .testTag(MonitoringTags.SERVICE_PREFIX + service.name)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(
                    service.name,
                    fontFamily = OrsikMono,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (service.active == false) {
                    val ask = stringResource(R.string.monitoring_ask_hermes)
                    val prompt = stringResource(R.string.monitoring_service_prompt, service.name)
                    TextButton(
                        onClick = { startTask(service.name, prompt) },
                        modifier = Modifier.testTag(MonitoringTags.ASK_PREFIX + service.name)
                    ) { Text(ask) }
                } else {
                    Text(label, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            stringResource(R.string.monitoring_services_footnote),
            style = OrsikType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun HermesTasks(startTask: (String, String) -> Unit) {
    val storage = stringResource(R.string.home_suggestion_storage)
    val storagePrompt = stringResource(R.string.monitoring_task_storage_prompt)
    val docker = stringResource(R.string.monitoring_task_docker)
    val dockerPrompt = stringResource(R.string.home_task_docker_prompt)
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.monitoring_via_hermes), style = OrsikType.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskTile(storage, OrsikIcons.Folder, Modifier.weight(1f).testTag(MonitoringTags.TASK_STORAGE)) { startTask(storage, storagePrompt) }
            TaskTile(docker, OrsikIcons.Container, Modifier.weight(1f).testTag(MonitoringTags.TASK_DOCKER)) { startTask(docker, dockerPrompt) }
        }
    }
}

@Composable
private fun TaskTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(14.dp),
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ValueRow(label: String, value: String?, big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(label, style = if (big) OrsikType.listTitle else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value ?: stringResource(R.string.home_server_no_value),
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (big) 22.sp else 14.sp
        )
    }
}

@Composable
private fun Bar(fill: Float?) {
    LinearProgressIndicator(
        progress = { fill?.coerceIn(0f, 1f) ?: 0f },
        drawStopIndicator = {},
        gapSize = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}

/** The CPU ring; a missing value draws only the track. */
@Composable
private fun Ring(fill: Float?, size: Dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (fill != null) {
            drawArc(color, -90f, 360f * fill.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** The local history as small bars, newest on the right; empty slots stay empty. */
@Composable
private fun HistoryBars(values: List<Float?>) {
    if (values.isEmpty()) return
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(28.dp)
            .clearAndSetSemantics {}
    ) {
        val slot = size.width / HISTORY_SIZE
        val gap = slot * 0.25f
        val start = HISTORY_SIZE - values.size
        for (i in 0 until HISTORY_SIZE) {
            val x = i * slot
            val value = if (i >= start) values[i - start] else null
            val h = if (value == null) 2f else (size.height * value).coerceAtLeast(2f)
            drawRect(
                color = if (value == null) empty else color.copy(alpha = 0.35f + 0.65f * (i.toFloat() / HISTORY_SIZE)),
                topLeft = Offset(x, size.height - h),
                size = Size(slot - gap, h)
            )
        }
    }
}

private fun gigabytes(bytes: Long, locale: Locale): String =
    String.format(locale, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

