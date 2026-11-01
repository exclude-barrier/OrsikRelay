package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType

/** Where an item sits in a grouped list; decides which corners are round. */
enum class GroupPosition { First, Middle, Last, Single }

/** The position of item [index] in a group of [count] items. */
fun groupPosition(index: Int, count: Int): GroupPosition {
    require(count > 0 && index in 0 until count) { "index $index outside group of $count" }
    return when {
        count == 1 -> GroupPosition.Single
        index == 0 -> GroupPosition.First
        index == count - 1 -> GroupPosition.Last
        else -> GroupPosition.Middle
    }
}

/** Outer corners [outer] (20 dp lists, 24 dp settings), inner corners 6 dp. */
fun GroupPosition.shape(outer: Dp = 20.dp, inner: Dp = 6.dp): RoundedCornerShape {
    val top = if (this == GroupPosition.First || this == GroupPosition.Single) outer else inner
    val bottom = if (this == GroupPosition.Last || this == GroupPosition.Single) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Gap between two items of one group. */
val GroupGap: Dp = 2.dp

/**
 * One row of a grouped list: a card-coloured block whose outer corners are
 * round only at the ends of its group. Callers space items by [GroupGap].
 */
@Composable
fun GroupedListItem(
    position: GroupPosition,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** Dense list rows (about 54 dp): title and supporting line stay on one line each. */
    compact: Boolean = false
) {
    val shape = position.shape()
    // Dense rows keep one line per text, but never at a large font: there the
    // text must stay fully readable, so it wraps again.
    val oneLine = compact && LocalDensity.current.fontScale < 1.3f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 54.dp else 68.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        onClickLabel = onClickLabel,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick
                    )
                    onClick != null -> Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 10.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = OrsikType.listTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                // Wraps instead of ellipsizing: at large font sizes the meta
                // line must stay fully readable.
                Text(
                    text = supporting,
                    style = OrsikType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (oneLine) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** The 40 dp rounded icon container in front of a list row. */
@Composable
fun ListIconBox(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 40 dp by default; dense lists use 32 dp with a 10 dp radius. */
    small: Boolean = false
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(if (small) 32.dp else 40.dp)
            .clip(if (small) RoundedCornerShape(10.dp) else OrsikShapes.iconContainer)
            .background(container)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (small) 18.dp else 20.dp))
    }
}
