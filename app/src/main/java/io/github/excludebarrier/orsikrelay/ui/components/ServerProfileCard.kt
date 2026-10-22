package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType

/** One small label on a [ServerProfileCard] ("AKTIV", "API-KEY", ...). */
data class ServerBadge(val text: String, val highlighted: Boolean = false)

/**
 * A stored server as a 24 dp card: icon container, name, address, badges
 * and a [status] at the end of the badge row. The active server gets a
 * 2 dp primary outline. [actions] is the overflow menu slot; a card that
 * cannot be tapped (the active server) stays [enabled] = false.
 */
@Composable
fun ServerProfileCard(
    name: String,
    address: String,
    active: Boolean,
    badges: List<ServerBadge>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    status: @Composable RowScope.() -> Unit = {},
    actions: @Composable () -> Unit = {}
) {
    val outline = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (active) 2.dp else 1.dp, outline, OrsikShapes.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(OrsikShapes.iconContainer)
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Icon(
                    OrsikIcons.Server,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = address,
                    style = OrsikType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            actions()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val locale = LocalConfiguration.current.locales[0]
            badges.forEach { badge ->
                Text(
                    text = badge.text.uppercase(locale),
                    style = OrsikType.badge,
                    color = if (badge.highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(OrsikShapes.badge)
                        .background(
                            if (badge.highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            status()
        }
    }
}
