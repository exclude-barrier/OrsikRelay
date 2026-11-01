package io.github.excludebarrier.orsikrelay.ui.sessions

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
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import java.time.ZoneId
import java.util.Locale

/**
 * One session in a grouped list (Agents tab, Home "Zuletzt"): source icon,
 * title, "source · n messages · time". A [running] session shows the
 * loading star and "läuft" instead of its icon; a pinned one a pin.
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
    onLongClick: (() -> Unit)? = null
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
        leading = {
            if (running) {
                WorkingGlyph(size = 40.dp, contentDescription = runningLabel)
            } else {
                ListIconBox(source.icon())
            }
        },
        trailing = when {
            running -> {
                {
                    Text(
                        text = runningLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
