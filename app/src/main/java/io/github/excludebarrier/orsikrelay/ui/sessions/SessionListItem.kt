package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import io.github.excludebarrier.orsikrelay.ui.components.rememberReducedMotion
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.ui.components.GroupPosition
import io.github.excludebarrier.orsikrelay.ui.components.GroupedListItem
import io.github.excludebarrier.orsikrelay.ui.components.ListIconBox
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import java.time.ZoneId
import java.util.Locale

/**
 * One session in a grouped list (Agents tab, Home "Zuletzt"): source icon,
 * title, "source · n messages · time", each on one line. A [running]
 * session keeps its icon and gets a "läuft" chip with a softly blinking dot;
 * one [awaitingApproval] a yellow "Freigabe" chip; a pinned one a pin.
 */
@Composable
internal fun SessionListItem(
    session: HermesSession,
    position: GroupPosition,
    locale: Locale,
    zone: ZoneId,
    showTimeOnly: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    showPin: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    awaitingApproval: Boolean = false
) {
    val title = session.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.session_fallback_title)
    val source = sessionSourceDescriptor(session.source)
    val lastActive = if (showTimeOnly) {
        SessionListFormatter.formatLastActiveTime(session.lastActiveEpochSeconds, locale, zone)
    } else {
        SessionListFormatter.formatLastActiveDate(session.lastActiveEpochSeconds, locale, zone)
    }
    val messageCount = session.messageCount?.let { count ->
        pluralStringResource(
            R.plurals.session_message_count,
            count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            SessionListFormatter.formatMessageCount(count, locale).orEmpty()
        )
    }
    val meta = buildMetaLine(source.displayName(), messageCount, lastActive)
    val runningLabel = stringResource(R.string.sessions_running)
    GroupedListItem(
        position = position,
        title = title,
        supporting = meta.takeIf { it.isNotEmpty() },
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClick?.let { stringResource(R.string.sessions_actions) },
        modifier = modifier,
        compact = true,
        leading = {
            val active = running || awaitingApproval
            ListIconBox(
                source.icon(),
                small = true,
                container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailing = when {
            awaitingApproval -> {
                { ApprovalChip(stringResource(R.string.sessions_awaiting_approval)) }
            }
            running -> {
                { RunningChip(runningLabel) }
            }
            showPin && session.pinned -> {
                {
                    Icon(
                        OrsikIcons.Pin,
                        contentDescription = stringResource(R.string.sessions_pinned),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            else -> null
        }
    )
}

@Composable
internal fun SessionSourceDescriptor.displayName(): String? = when (this) {
    SessionSourceDescriptor.Missing -> null
    SessionSourceDescriptor.Terminal -> stringResource(R.string.session_source_terminal)
    SessionSourceDescriptor.Telegram -> stringResource(R.string.session_source_telegram)
    SessionSourceDescriptor.Email -> stringResource(R.string.session_source_email)
    SessionSourceDescriptor.Sms -> stringResource(R.string.session_source_sms)
    SessionSourceDescriptor.WhatsApp -> stringResource(R.string.session_source_whatsapp)
    SessionSourceDescriptor.Signal -> stringResource(R.string.session_source_signal)
    SessionSourceDescriptor.Scheduled -> stringResource(R.string.session_source_scheduled)
    is SessionSourceDescriptor.Other -> rawName
}

internal fun buildMetaLine(sourceName: String?, messageCount: String?, lastActive: String?): String =
    listOfNotNull(sourceName, messageCount, lastActive).joinToString(separator = " · ")

@Composable
private fun RunningChip(label: String) {
    val reducedMotion = rememberReducedMotion()
    // The dot breathes while the run goes on; with animations off it rests.
    val dotAlpha = if (reducedMotion) {
        1f
    } else {
        rememberInfiniteTransition(label = "runningDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "runningDotAlpha"
        ).value
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(OrsikShapes.pill)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(SESSION_RUNNING_CHIP_TAG)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onPrimaryContainer)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ApprovalChip(label: String) {
    val colors = OrsikTheme.statusColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(OrsikShapes.pill)
            .background(colors.warnContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(SESSION_APPROVAL_CHIP_TAG)
    ) {
        Icon(OrsikIcons.Shield, contentDescription = null, tint = colors.onWarnContainer, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onWarnContainer)
    }
}

internal const val SESSION_RUNNING_CHIP_TAG = "session_running_chip"
internal const val SESSION_APPROVAL_CHIP_TAG = "session_approval_chip"
