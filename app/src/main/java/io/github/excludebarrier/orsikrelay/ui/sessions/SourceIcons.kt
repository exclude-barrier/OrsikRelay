package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
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

internal fun SessionSourceDescriptor.icon(): ImageVector? = when (this) {
    SessionSourceDescriptor.Missing -> null
    SessionSourceDescriptor.Terminal -> TerminalSourceIcon
    SessionSourceDescriptor.Telegram -> Icons.AutoMirrored.Filled.Send
    SessionSourceDescriptor.Email -> Icons.Filled.Email
    SessionSourceDescriptor.Sms,
    SessionSourceDescriptor.WhatsApp,
    SessionSourceDescriptor.Signal -> Icons.Filled.Phone
    SessionSourceDescriptor.Scheduled -> Icons.Filled.DateRange
    is SessionSourceDescriptor.Other -> ChatSourceIcon
}

/*
 * Terminal and Chat are Material Icons, Copyright Google LLC,
 * licensed under the Apache License 2.0 (https://fonts.google.com/icons).
 */
private val TerminalSourceIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.Terminal",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 4f)
            horizontalLineTo(4f)
            curveTo(2.89f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.89f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.11f, 4f, 20f, 4f)
            close()
            moveTo(20f, 18f)
            horizontalLineTo(4f)
            verticalLineTo(8f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            close()
            moveTo(18f, 17f)
            horizontalLineTo(12f)
            verticalLineTo(15f)
            horizontalLineTo(18f)
            verticalLineTo(17f)
            close()
            moveTo(7.5f, 17f)
            lineTo(6.09f, 15.59f)
            lineTo(8.67f, 13f)
            lineTo(6.08f, 10.41f)
            lineTo(7.5f, 9f)
            lineTo(11.5f, 13f)
            lineTo(7.5f, 17f)
            close()
        }
    }.build()
}

private val ChatSourceIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.Chat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(2f, 22f)
            lineToRelative(4f, -4f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(4f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(6f, 9f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(2f)
            horizontalLineTo(6f)
            verticalLineTo(9f)
            close()
            moveToRelative(8f, 5f)
            horizontalLineTo(6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            close()
            moveToRelative(4f, -6f)
            horizontalLineTo(6f)
            verticalLineTo(6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(2f)
            close()
        }
    }.build()
}
