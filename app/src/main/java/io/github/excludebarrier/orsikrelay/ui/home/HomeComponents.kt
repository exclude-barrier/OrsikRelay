package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.ui.profiles.DnsIcon
import io.github.excludebarrier.orsikrelay.ui.sessions.ChatSourceIcon
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionRow
import io.github.excludebarrier.orsikrelay.ui.sessions.TerminalSourceIcon
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Test tags of the Home screen. */
object HomeTags {
    const val SCREEN = "home_screen"
    const val LIST = "home_list"
    const val SERVER_NAME = "home_server_name"
    const val STATUS = "home_status"
    const val SETTINGS = "home_settings"
    const val APPROVAL = "home_approval"
    const val APPROVAL_REVIEW = "home_approval_review"
    const val RUN_ROW = "home_run_row"
    const val SERVER_STRIP = "home_server_strip"
    const val QUICK_CHAT = "home_quick_chat"
    const val QUICK_TILE_PREFIX = "home_quick_"
    const val RECENT_ROW = "home_recent_row"
    const val ALL_SESSIONS = "home_all_sessions"
    const val OFFLINE = "home_offline"
    const val OFFLINE_RETRY = "home_offline_retry"
    const val OFFLINE_SWITCH = "home_offline_switch"
    const val SKELETON = "home_skeleton"
    const val COMPOSER_INPUT = "home_composer_input"
    const val COMPOSER_SEND = "home_composer_send"
    const val COMPOSER_ATTACH = "home_composer_attach"
    const val COMPOSER_AGENT = "home_composer_agent"
    const val SUGGESTION_PREFIX = "home_suggestion_"
    const val NOTICE = "home_notice"
    const val PICKER = "home_picker"
    const val PICKER_HERMES = "home_picker_hermes"
    const val PICKER_PREVIEW_PREFIX = "home_picker_preview_"
}

internal val CardShape = RoundedCornerShape(24.dp)

/** Amber of the approval card; the theme has no warning role. */
private data class ApprovalColors(val container: Color, val content: Color, val button: Color, val onButton: Color)

@Composable
private fun approvalColors(): ApprovalColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        ApprovalColors(Color(0xFF4D3B0B), Color(0xFFFFE7A8), Color(0xFFF2B544), Color(0xFF2B1F00))
    } else {
        ApprovalColors(Color(0xFFFFF0C2), Color(0xFF3D2C00), Color(0xFF8A5A00), Color(0xFFFFFFFF))
    }

/** Whether a reachable-server green is readable on this background. */
@Composable
private fun statusGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF3FB950) else Color(0xFF1A7F37)

@Composable
fun ServerHeader(
    serverName: String,
    connection: HomeConnection,
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenSettings,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.testTag(HomeTags.SETTINGS)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_open_settings))
            }
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
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { heading() }
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        val (dotColor, label) = when (connection) {
            HomeConnection.Connected -> statusGreen() to stringResource(R.string.home_status_connected)
            HomeConnection.Checking -> MaterialTheme.colorScheme.primary to stringResource(R.string.home_status_checking)
            is HomeConnection.Unreachable -> MaterialTheme.colorScheme.error to stringResource(R.string.home_status_unreachable)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag(HomeTags.STATUS)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connection is HomeConnection.Unreachable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        trailing()
    }
}

@Composable
fun PreviewBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.home_preview_badge)) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = text.uppercase(locale),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun ApprovalBanner(title: String?, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val colors = approvalColors()
    Surface(
        shape = CardShape,
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier
            .fillMaxWidth()
            .testTag(HomeTags.APPROVAL)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(cookieShape())
                    .background(colors.button)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.onButton, modifier = Modifier.size(20.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_approval_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.home_approval_subtitle,
                        title ?: stringResource(R.string.home_run_untitled)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onReview,
                colors = ButtonDefaults.buttonColors(containerColor = colors.button, contentColor = colors.onButton),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(HomeTags.APPROVAL_REVIEW)
            ) {
                Text(stringResource(R.string.home_approval_review), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ActiveRunRow(item: HomeRunItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onClick)
            .testTag(HomeTags.RUN_ROW)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(cookieShape())
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                if (item.uncertain) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                } else {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = item.title ?: stringResource(R.string.home_run_untitled),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(if (item.uncertain) R.string.home_run_uncertain else R.string.home_run_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun ServerStatusStrip(connected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(text = stringResource(R.string.home_section_server)) {
            if (connected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusGreen().copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = statusGreen(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.home_server_reachable),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusGreen()
                    )
                }
            }
        }
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTags.SERVER_STRIP)
        ) {
            Column(Modifier.padding(16.dp)) {
                val noValue = stringResource(R.string.home_server_no_value)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        R.string.home_server_cpu,
                        R.string.home_server_ram,
                        R.string.home_server_gpu,
                        R.string.home_server_services
                    ).forEach { labelRes ->
                        val label = stringResource(labelRes)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clearAndSetSemantics { contentDescription = "$label: $noValue" }
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
                            DashedBar()
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    PreviewBadge()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_server_preview_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DashedBar() {
    val color = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .dashedBorder(color, 4.dp)
    )
}

/** Dashed rounded outline: the "not yet available" look of the design. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        )
    )
}

@Composable
fun QuickActionGrid(onOpenChat: () -> Unit, modifier: Modifier = Modifier) {
    val tiles = listOf(
        QuickTile("chat", R.string.home_quick_chat, ChatSourceIcon, available = true),
        QuickTile("server", R.string.home_quick_server, DnsIcon),
        QuickTile("terminal", R.string.home_quick_terminal, TerminalSourceIcon),
        QuickTile("files", R.string.home_quick_files, FolderIcon),
        QuickTile("docker", R.string.home_quick_docker, Icons.Filled.Build),
        QuickTile("projects", R.string.home_quick_projects, CodeIcon)
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    QuickActionTile(
                        label = stringResource(tile.labelRes),
                        icon = tile.icon,
                        available = tile.available,
                        onClick = onOpenChat,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(HomeTags.QUICK_TILE_PREFIX + tile.key)
                    )
                }
            }
        }
    }
}

private class QuickTile(
    val key: String,
    val labelRes: Int,
    val icon: ImageVector,
    val available: Boolean = false
)

@Composable
fun QuickActionTile(
    label: String,
    icon: ImageVector,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val soon = stringResource(R.string.home_soon_badge)
    val outline = MaterialTheme.colorScheme.outline
    val base = modifier
        .heightIn(min = 88.dp)
        .clip(shape)
    val styled = if (available) {
        base
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
    } else {
        base
            .dashedBorder(outline, 20.dp)
            .semantics(mergeDescendants = true) {
                disabled()
                contentDescription = "$label, $soon"
            }
    }
    val content = if (available) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = styled.padding(12.dp)) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        if (!available) {
            Text(
                text = soon,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = content,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (available) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(top = 32.dp)
        )
    }
}

@Composable
fun RecentSessionRow(
    session: HermesSession,
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
    Box(modifier = modifier.testTag(HomeTags.RECENT_ROW)) {
        SessionRow(
            session = session,
            locale = locale,
            zone = zone,
            showTimeOnly = showTimeOnly,
            onClick = onClick
        )
    }
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
        shape = CardShape,
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
    val block = MaterialTheme.colorScheme.surfaceContainer
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag(HomeTags.SKELETON)
            .clearAndSetSemantics {}
    ) {
        Box(Modifier.width(120.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth().height(72.dp).clip(CardShape).background(block))
        Box(Modifier.width(80.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth().height(96.dp).clip(CardShape).background(block))
    }
}

/** A spring-based press scale for the round dock buttons. */
@Composable
internal fun pressScale(pressed: Boolean): Float =
    animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    ).value

internal fun Modifier.scaled(scale: Float): Modifier = this.scale(scale)
