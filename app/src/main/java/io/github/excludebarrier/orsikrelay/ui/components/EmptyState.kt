package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType

internal const val EMPTY_STATE_ICON_TAG = "empty_state_icon"

/**
 * A calm empty, loading or error state: a [leading] mark (an icon in its
 * container by default, e.g. the loading star while loading), a short
 * [title], an optional [hint] and an optional [action] below.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
    hintTestTag: String? = null,
    titleTestTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(horizontal = 32.dp)) {
        when {
            leading != null -> leading()
            icon != null -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag(EMPTY_STATE_ICON_TAG)
                    .size(56.dp)
                    .clip(ExpressiveShape.Cookie.shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            text = title,
            style = OrsikType.listTitle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .then(if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier)
        )
        if (hint != null) {
            Text(
                text = hint,
                style = OrsikType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .then(if (hintTestTag != null) Modifier.testTag(hintTestTag) else Modifier)
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = 16.dp)) { action() }
        }
    }
}
