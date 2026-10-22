package io.github.excludebarrier.orsikrelay.ui.home

import java.util.Locale
import kotlin.math.roundToInt
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.ui.components.ComposerDockTags
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.GroupPosition
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLoadingIndicator
import io.github.excludebarrier.orsikrelay.ui.components.PreviewNote
import io.github.excludebarrier.orsikrelay.ui.components.PreviewTile
import io.github.excludebarrier.orsikrelay.ui.components.dashedBorder
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionListItem
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Test tags of the Home screen. */
object HomeTags {
    const val SCREEN = "home_screen"
    const val COMPACT_HEADER = "home_compact_header"
    const val COMPACT_SETTINGS = "home_compact_settings"
    const val SUGGESTIONS = "home_suggestions"
    const val DOCK = "home_dock"
    const val LIST = "home_list"
    const val SERVER_NAME = "home_server_name"
    const val STATUS = "home_status"
    const val SETTINGS = "home_settings"
    const val APPROVAL = "home_approval"
    const val APPROVAL_REVIEW = "home_approval_review"
    const val RUN_ROW = "home_run_row"
    const val SERVER_STRIP = "home_server_strip"
    const val SERVER_SERVICES_DOWN = "home_server_services_down"
    const val QUICK_CHAT = "home_quick_chat"
    const val QUICK_TILE_PREFIX = "home_quick_"
    const val RECENT_ROW = "home_recent_row"
    const val ALL_SESSIONS = "home_all_sessions"
    const val OFFLINE = "home_offline"
    const val OFFLINE_RETRY = "home_offline_retry"
    const val OFFLINE_SWITCH = "home_offline_switch"
    const val SKELETON = "home_skeleton"
    const val COMPOSER_INPUT = ComposerDockTags.INPUT
    const val COMPOSER_SEND = ComposerDockTags.SEND
    const val COMPOSER_AGENT = ComposerDockTags.AGENT
    const val SUGGESTION_PREFIX = "home_suggestion_"
    const val NOTICE = "home_notice"
    const val PICKER = "home_picker"
    const val PICKER_HERMES = "home_picker_hermes"
    const val PICKER_PREVIEW_PREFIX = "home_picker_preview_"
}

@Composable
private fun connectionDot(connection: HomeConnection): Pair<Color, Color> {
    val status = OrsikTheme.statusColors
    return when (connection) {
        HomeConnection.Connected -> status.ok to status.okContainer
        HomeConnection.Checking -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        is HomeConnection.Unreachable -> MaterialTheme.colorScheme.error to Color.Transparent
    }
}

@Composable
private fun StatusDot(connection: HomeConnection, ring: Boolean) {
    val (dot, halo) = connectionDot(connection)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
        if (ring) Box(Modifier.size(14.dp).clip(CircleShape).background(halo))
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit, filled: Boolean, testTag: String) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .testTag(testTag)
    ) {
        Icon(
            Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.home_open_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** The large Home header: brand line, server switcher and status. */
@Composable
fun ServerHeader(
    serverName: String,
    connection: HomeConnection,
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(OrsikIcons.Relay, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.04.em,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val switchLabel = stringResource(R.string.home_switch_server)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = switchLabel, onClick = onSwitchServer)
                    .heightIn(min = 48.dp)
                    .testTag(HomeTags.SERVER_NAME)
            ) {
                Text(
                    text = serverName,
                    style = OrsikType.screenTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics { heading() }
                )
                Icon(OrsikIcons.ChevronDown, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(28.dp))
            }
            val label = when (connection) {
                HomeConnection.Connected -> stringResource(R.string.home_status_connected)
                HomeConnection.Checking -> stringResource(R.string.home_status_checking)
                is HomeConnection.Unreachable -> stringResource(R.string.home_status_unreachable)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(HomeTags.STATUS)
            ) {
                StatusDot(connection, ring = true)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label,
                    fontSize = 13.5.sp,
                    color = if (connection is HomeConnection.Unreachable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        SettingsButton(onClick = onOpenSettings, filled = true, testTag = HomeTags.SETTINGS)
    }
}

/** The compact bar that replaces [ServerHeader] once it scrolled away. */
@Composable
fun CompactServerHeader(
    serverName: String,
    connection: HomeConnection,
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val switchLabel = stringResource(R.string.home_switch_server)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(line, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .testTag(HomeTags.COMPACT_HEADER)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = switchLabel, onClick = onSwitchServer)
                .heightIn(min = 48.dp)
        ) {
            Text(
                text = serverName,
                style = OrsikType.compactTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(OrsikIcons.ChevronDown, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        StatusDot(connection, ring = false)
        Spacer(Modifier.weight(1f))
        SettingsButton(onClick = onOpenSettings, filled = false, testTag = HomeTags.COMPACT_SETTINGS)
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    countBadge: Int? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 26.dp)
            .heightIn(min = 32.dp)
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    ) {
        Text(
            text = text,
            style = OrsikType.sectionTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        if (countBadge != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .heightIn(min = 22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 7.dp)
            ) {
                Text(
                    text = countBadge.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** From this font scale on, the approval card stacks "Prüfen" below its text. */
private const val STACKED_FONT_SCALE = 1.3f

@Composable
fun ApprovalBanner(title: String?, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OrsikTheme.statusColors
    // Side by side the button squeezes the text column; at large font sizes
    // that broke words mid-word, so the button moves below the text.
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val review: @Composable () -> Unit = {
        Button(
            onClick = onReview,
            shape = OrsikShapes.pill,
            colors = ButtonDefaults.buttonColors(containerColor = colors.warn, contentColor = colors.onWarn),
            modifier = Modifier.testTag(HomeTags.APPROVAL_REVIEW)
        ) {
            Text(stringResource(R.string.home_approval_review), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(colors.warnContainer)
            .padding(14.dp)
            .testTag(HomeTags.APPROVAL)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(ExpressiveShape.Sunny.shape)
                    .background(colors.warn)
            ) {
                Icon(OrsikIcons.Shield, contentDescription = null, tint = colors.onWarn, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_approval_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onWarnContainer
                )
                Text(
                    text = stringResource(
                        R.string.home_approval_subtitle,
                        title ?: stringResource(R.string.home_run_untitled)
                    ),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.onWarnContainer,
                    maxLines = if (stacked) 3 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!stacked) review()
        }
        if (stacked) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
                review()
            }
        }
    }
}

@Composable
fun ActiveRunRow(item: HomeRunItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = stringResource(if (item.uncertain) R.string.home_run_uncertain else R.string.home_run_running)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag(HomeTags.RUN_ROW)
    ) {
        if (item.uncertain) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            OrsikLoadingIndicator(contentDescription = status)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title ?: stringResource(R.string.home_run_untitled),
                style = OrsikType.listTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = status, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(OrsikIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ServerStatusStrip(metrics: HostMetricsResult?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // No status chip here: the server card above already says whether
        // the server is connected.
        SectionTitle(text = stringResource(R.string.home_section_server))
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrsikShapes.card)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp)
                .testTag(HomeTags.SERVER_STRIP)
        ) {
            val ready = (metrics as? HostMetricsResult.Ready)?.metrics
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                hostTiles(ready).forEach { tile -> HostTileView(tile, Modifier.weight(1f)) }
            }
            when (metrics) {
                is HostMetricsResult.Ready -> {
                    val down = metrics.metrics.services.filter { it.active == false }.map { it.name }
                    if (down.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.home_server_services_down, down.joinToString(", ")),
                            style = OrsikType.meta,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(HomeTags.SERVER_SERVICES_DOWN)
                        )
                    }
                }
                HostMetricsResult.Failed -> PreviewNote(stringResource(R.string.home_server_unreadable))
                // An older bridge: the card keeps its preview.
                HostMetricsResult.Unsupported -> PreviewNote(stringResource(R.string.home_server_preview_text))
                // Not read yet (or the server is unreachable): placeholders only.
                null -> Unit
            }
        }
    }
}

/** One value of the server card: label, value text, a fill (0..1) or none, and a warning tone. */
private class HostTile(
    val label: String,
    val value: String?,
    val fill: Float?,
    val warn: Boolean = false,
    val detail: String? = null
)

@Composable
private fun hostTiles(metrics: HostMetrics?): List<HostTile> {
    val locale = LocalConfiguration.current.locales[0]
    val cpu = stringResource(R.string.home_server_cpu)
    val ram = stringResource(R.string.home_server_ram)
    val gpu = stringResource(R.string.home_server_gpu)
    val services = stringResource(R.string.home_server_services)
    val gpuNone = stringResource(R.string.home_server_gpu_none)
    val cores = metrics?.cores?.let { pluralCores(it) }
    if (metrics == null) return listOf(cpu, ram, gpu, services).map { HostTile(it, null, null) }
    val used = metrics.memoryUsedBytes
    val total = metrics.memoryTotalBytes
    val active = metrics.services.count { it.active == true }
    // Only a confirmed stop is a warning; "could not ask" is not.
    val stopped = metrics.services.any { it.active == false }
    val count = metrics.services.size
    return listOf(
        HostTile(cpu, metrics.cpuPercent?.let { "${it.roundToInt()} %" }, metrics.cpuPercent?.let { (it / 100).toFloat() }, detail = cores),
        HostTile(
            ram,
            if (used != null && total != null) "${gibibytes(used, locale)}/${gibibytes(total, locale)} GB" else null,
            if (used != null && total != null && total > 0) used.toFloat() / total else null
        ),
        metrics.gpu?.let { HostTile(gpu, "${it.utilizationPercent} %", it.utilizationPercent / 100f, detail = it.name) }
            ?: HostTile(gpu, gpuNone, null),
        HostTile(
            services,
            if (count == 0) null else "$active/$count",
            if (count == 0) null else active.toFloat() / count,
            warn = stopped
        )
    )
}

@Composable
private fun pluralCores(cores: Int): String = stringResource(R.string.home_server_cores, cores)

private fun gibibytes(bytes: Long, locale: Locale): String =
    String.format(locale, "%.0f", bytes / (1024.0 * 1024.0 * 1024.0))

@Composable
private fun HostTileView(tile: HostTile, modifier: Modifier) {
    val noValue = stringResource(R.string.home_server_no_value)
    val value = tile.value
    val tone = if (tile.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = listOfNotNull(tile.label, value ?: noValue, tile.detail).joinToString(": ")
        }
    ) {
        Text(tile.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value ?: "—",
            fontFamily = OrsikMono,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                tile.warn -> tone
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        val fill = tile.fill
        if (fill == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .dashedBorder(MaterialTheme.colorScheme.outline, 3.dp)
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(tone)
                )
            }
        }
    }
}

@Composable
fun QuickActionGrid(
    onNewChat: () -> Unit,
    onOpenServer: () -> Unit,
    onQuickTask: (QuickTask) -> Unit,
    modifier: Modifier = Modifier
) {
    // KI-Chat starts a new chat, Server opens the Server tab; the four tool
    // tiles start a new chat with a prepared request in the composer (sent
    // only when the user sends it, so Hermes' approvals apply as usual).
    val cells = listOf(
        QuickTile("chat", R.string.home_quick_chat, OrsikIcons.Chat, onClick = onNewChat, filled = true),
        QuickTile("server", R.string.home_quick_server, OrsikIcons.Server, onClick = onOpenServer)
    ) + QuickTask.entries.map { task -> QuickTile(task.key, task.labelRes, task.icon, onClick = { onQuickTask(task) }) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    val tileModifier = Modifier
                        .weight(1f)
                        .testTag(HomeTags.QUICK_TILE_PREFIX + tile.key)
                    val onClick = tile.onClick
                    if (onClick != null) {
                        LiveTile(stringResource(tile.labelRes), tile.icon, tile.filled, onClick, tileModifier)
                    } else {
                        PreviewTile(label = stringResource(tile.labelRes), icon = tile.icon, modifier = tileModifier)
                    }
                }
            }
        }
    }
}

/**
 * A tool tile: a new chat titled [labelRes] whose composer holds [promptRes],
 * a prepared request for Hermes. Nothing is sent until the user sends it.
 */
enum class QuickTask(val key: String, val labelRes: Int, val promptRes: Int, val icon: ImageVector) {
    Terminal("terminal", R.string.home_quick_terminal, R.string.home_task_terminal_prompt, OrsikIcons.Terminal),
    Files("files", R.string.home_quick_files, R.string.home_task_files_prompt, OrsikIcons.Folder),
    Docker("docker", R.string.home_quick_docker, R.string.home_task_docker_prompt, OrsikIcons.Container),
    Projects("projects", R.string.home_quick_projects, R.string.home_task_projects_prompt, OrsikIcons.Code)
}

/** One quick-access tile; without [onClick] it is a disabled "Bald" preview. */
private class QuickTile(
    val key: String,
    val labelRes: Int,
    val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
    val filled: Boolean = false
)

/**
 * A quick-access tile that works today: [filled] is the primary "KI-Chat"
 * tile, otherwise a card-coloured tile (Server).
 */
@Composable
internal fun LiveTile(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val content = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .height(92.dp)
            .clip(OrsikShapes.card)
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) content else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RecentSessionRow(
    session: HermesSession,
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone()
) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = clock.zone
    val today = LocalDate.now(clock)
    val showTimeOnly = session.lastActiveEpochSeconds?.let {
        Instant.ofEpochSecond(it).atZone(zone).toLocalDate() == today
    } ?: false
    SessionListItem(
        session = session,
        position = position,
        locale = locale,
        zone = zone,
        showTimeOnly = showTimeOnly,
        onClick = onClick,
        modifier = modifier.testTag(HomeTags.RECENT_ROW)
    )
}

@Composable
fun OfflineCard(
    serverName: String,
    reason: ConnectionFailureReason,
    onRetry: () -> Unit,
    onSwitchServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = OrsikShapes.card,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .testTag(HomeTags.OFFLINE)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_offline_title, serverName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(reason.textRes()) + " " + stringResource(R.string.home_offline_unaffected),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(HomeTags.OFFLINE_RETRY)
                ) { Text(stringResource(R.string.home_offline_retry)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSwitchServer,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(HomeTags.OFFLINE_SWITCH)
                ) { Text(stringResource(R.string.home_offline_switch)) }
            }
        }
    }
}

private fun ConnectionFailureReason.textRes(): Int = when (this) {
    ConnectionFailureReason.INVALID_INPUT -> R.string.onboarding_error_invalid_input_text
    ConnectionFailureReason.INVALID_CREDENTIALS -> R.string.onboarding_error_invalid_credentials_text
    ConnectionFailureReason.RATE_LIMITED -> R.string.onboarding_error_rate_limited_text
    ConnectionFailureReason.INCOMPATIBLE_API -> R.string.onboarding_error_incompatible_api_text
    ConnectionFailureReason.TLS -> R.string.onboarding_error_tls_text
    ConnectionFailureReason.TIMEOUT -> R.string.onboarding_error_timeout_text
    ConnectionFailureReason.NETWORK -> R.string.onboarding_error_network_text
    ConnectionFailureReason.HTTP -> R.string.onboarding_error_http_text
}

/** Placeholder blocks while the connection is checked. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag(HomeTags.SKELETON)
            .clearAndSetSemantics {}
    ) {
        Box(Modifier.width(120.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth().height(72.dp).clip(OrsikShapes.card).background(block))
        Box(Modifier.width(80.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth().height(96.dp).clip(OrsikShapes.card).background(block))
    }
}

