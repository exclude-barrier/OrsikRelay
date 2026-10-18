package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Severity of a [ChatNotice]. Errors use the warning icon in the error color,
 * informational notices the info icon in the primary color.
 */
internal enum class ChatNoticeTone { Error, Info }

/**
 * One action of a [ChatNotice]. [ChatNotice] renders it as a
 * [FilledTonalButton] with the Material minimum touch target.
 */
internal data class ChatNoticeAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val testTag: String? = null
)

/**
 * The single card used for chat failure and recovery notices: a
 * Material 3 [Surface] with a leading icon, a title, up to two optional text
 * lines and up to two right-aligned actions. Text is always left-aligned and
 * every card uses the same type scale, so the notices stay consistent and
 * readable at large font scales (the card grows instead of clipping).
 */
@Composable
internal fun ChatNotice(
    tone: ChatNoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    textTestTag: String? = null,
    secondaryText: String? = null,
    actions: List<ChatNoticeAction> = emptyList()
) {
    val icon = when (tone) {
        ChatNoticeTone.Error -> Icons.Filled.Warning
        ChatNoticeTone.Info -> Icons.Filled.Info
    }
    val iconTint = when (tone) {
        ChatNoticeTone.Error -> MaterialTheme.colorScheme.error
        ChatNoticeTone.Info -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .then(if (textTestTag != null) Modifier.testTag(textTestTag) else Modifier)
                    )
                }
                if (secondaryText != null) {
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (actions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        actions.take(2).forEach { action ->
                            FilledTonalButton(
                                onClick = action.onClick,
                                enabled = action.enabled,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .heightIn(min = 48.dp)
                                    .then(
                                        if (action.testTag != null) {
                                            Modifier.testTag(action.testTag)
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
