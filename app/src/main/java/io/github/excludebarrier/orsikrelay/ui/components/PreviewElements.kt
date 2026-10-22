package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType

/** Dashed rounded outline: the "not yet available" look of the design. */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val inset = stroke / 2
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        )
    )
}

/** The grey "VORSCHAU" label of features that only show their future place. */
@Composable
fun PreviewBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.home_preview_badge)) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = text.uppercase(locale),
        style = OrsikType.badge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(OrsikShapes.badge)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * A disabled, dashed tile of a feature that is not there yet ("Bald").
 * [stacked] tiles put the icon above the label (Home quick access); flat
 * tiles keep everything in one row (server management).
 */
@Composable
fun PreviewTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    stacked: Boolean = true
) {
    val soon = stringResource(R.string.home_soon_badge)
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    val base = modifier
        .dashedBorder(MaterialTheme.colorScheme.outlineVariant, if (stacked) 24.dp else 20.dp, 1.5.dp)
        .semantics(mergeDescendants = true) {
            disabled()
            contentDescription = "$label, $soon"
        }
    if (stacked) {
        Box(modifier = base.height(92.dp).padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(
                text = soon,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = content,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = base
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(
                text = label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = soon, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = content)
        }
    }
}

/** A row with [PreviewBadge] and its explanation. */
@Composable
fun PreviewNote(text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        PreviewBadge()
        Spacer(Modifier.width(8.dp))
        Text(text = text, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
