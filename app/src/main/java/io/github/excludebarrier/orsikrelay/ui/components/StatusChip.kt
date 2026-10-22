package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme

/** Meaning of a [StatusChip]. */
enum class StatusKind { Ok, Neutral, Error }

/**
 * A small status label: [StatusKind.Ok] is the green "Erreichbar" pill with
 * a check mark; the other kinds are plain coloured text, as in the design.
 */
@Composable
fun StatusChip(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    when (kind) {
        StatusKind.Ok -> {
            val colors = OrsikTheme.statusColors
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = modifier
                    .heightIn(min = 30.dp)
                    .clip(OrsikShapes.pill)
                    .background(colors.okContainer)
                    .padding(start = 9.dp, end = 12.dp)
            ) {
                Icon(OrsikIcons.Check, contentDescription = null, tint = colors.okText, modifier = Modifier.size(16.dp))
                Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.okText)
            }
        }
        StatusKind.Neutral, StatusKind.Error -> Text(
            text = text,
            fontSize = 13.sp,
            color = if (kind == StatusKind.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }
}
