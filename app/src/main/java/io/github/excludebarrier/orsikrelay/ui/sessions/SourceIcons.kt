package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import java.util.Locale

/**
 * Visual identity of one Hermes session source.
 *
 * [Other] keeps the server's raw value, so an unknown source still gets a
 * readable name instead of disappearing.
 */
sealed interface SessionSourceDescriptor {
    data object Missing : SessionSourceDescriptor
    data object Terminal : SessionSourceDescriptor
    data object Telegram : SessionSourceDescriptor
    data object Email : SessionSourceDescriptor
    data object Sms : SessionSourceDescriptor
    data object WhatsApp : SessionSourceDescriptor
    data object Signal : SessionSourceDescriptor
    data object Scheduled : SessionSourceDescriptor
    data class Other(val rawName: String) : SessionSourceDescriptor
}

/**
 * Pure source mapping. Comparison is case-insensitive on the trimmed value;
 * the raw (trimmed) value is preserved for unknown sources.
 */
fun sessionSourceDescriptor(source: String?): SessionSourceDescriptor {
    val trimmed = source?.trim().orEmpty()
    if (trimmed.isEmpty()) return SessionSourceDescriptor.Missing
    return when (trimmed.lowercase(Locale.ROOT)) {
        "cli", "tui", "local" -> SessionSourceDescriptor.Terminal
        "telegram" -> SessionSourceDescriptor.Telegram
        "email" -> SessionSourceDescriptor.Email
        "sms" -> SessionSourceDescriptor.Sms
        "whatsapp", "whatsapp_cloud" -> SessionSourceDescriptor.WhatsApp
        "signal" -> SessionSourceDescriptor.Signal
        "cron" -> SessionSourceDescriptor.Scheduled
        else -> SessionSourceDescriptor.Other(trimmed)
    }
}

internal fun SessionSourceDescriptor.icon(): ImageVector = when (this) {
    SessionSourceDescriptor.Missing -> OrsikIcons.Chat
    SessionSourceDescriptor.Terminal -> OrsikIcons.TerminalBox
    SessionSourceDescriptor.Telegram -> OrsikIcons.Send
    SessionSourceDescriptor.Email -> Icons.Filled.Email
    SessionSourceDescriptor.Sms,
    SessionSourceDescriptor.WhatsApp,
    SessionSourceDescriptor.Signal -> Icons.Filled.Phone
    SessionSourceDescriptor.Scheduled -> OrsikIcons.Calendar
    is SessionSourceDescriptor.Other -> OrsikIcons.Chat
}
