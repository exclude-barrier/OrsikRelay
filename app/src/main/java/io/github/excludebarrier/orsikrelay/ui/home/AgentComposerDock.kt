package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.PreviewBadge

private val DockShape = RoundedCornerShape(32.dp)

/** The fixed suggestion prompts; a tap only fills the input. */
internal val HomeSuggestions = listOf(
    "storage" to R.string.home_suggestion_storage,
    "docker" to R.string.home_suggestion_docker,
    "backup" to R.string.home_suggestion_backup
)

@Composable
fun SuggestionChips(enabled: Boolean, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(HomeSuggestions, key = { it.first }) { (key, textRes) ->
            val text = stringResource(textRes)
            SuggestionChip(
                onClick = { onSuggestion(text) },
                enabled = enabled,
                label = { Text(text) },
                modifier = Modifier.testTag(HomeTags.SUGGESTION_PREFIX + key)
            )
        }
    }
}

@Composable
fun AgentComposerDock(
    draft: String,
    onDraftChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    canSend: Boolean,
    sending: Boolean,
    attachmentName: String?,
    attachmentBusy: Boolean,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onOpenAgentPicker: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = DockShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(6.dp)) {
            if (attachmentName != null) {
                InputChip(
                    selected = false,
                    onClick = onRemoveAttachment,
                    label = { Text(attachmentName, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.home_composer_remove_attachment, attachmentName),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(
                    enabled = enabled,
                    onClick = onOpenAgentPicker,
                    modifier = Modifier.testTag(HomeTags.COMPOSER_AGENT)
                )
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    placeholder = { Text(hint, maxLines = 1) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HomeTags.COMPOSER_INPUT)
                )
                IconButton(
                    onClick = onAttach,
                    enabled = enabled && !attachmentBusy && attachmentName == null,
                    modifier = Modifier.testTag(HomeTags.COMPOSER_ATTACH)
                ) {
                    if (attachmentBusy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_composer_attach))
                    }
                }
                SendButton(enabled = canSend, sending = sending, onClick = onSend)
            }
        }
    }
}

@Composable
private fun AgentAvatar(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.home_composer_agent)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(ExpressiveShape.Cookie.shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text(
            text = "H",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.home_composer_send)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .scaled(pressScale(pressed))
            .clip(ExpressiveShape.Cookie.shape)
            .background(container)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = description }
            .testTag(HomeTags.COMPOSER_SEND)
    ) {
        if (sending) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = content)
        }
    }
}

/** The harnesses the picker lists; only Hermes can be chosen today. */
private class PreviewHarness(val key: String, val name: String, val initial: String)

private val PreviewHarnesses = listOf(
    PreviewHarness("claude_code", "Claude Code", "C"),
    PreviewHarness("codex", "OpenAI Codex", "X"),
    PreviewHarness("omp", "OMP", "O")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessPickerSheet(serverName: String, connected: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Five short rows: open fully instead of a half-height first stop.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(HomeTags.PICKER)
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.home_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.home_picker_subtitle, serverName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            HarnessRow(
                initial = "H",
                name = "Hermes",
                detail = if (connected) stringResource(R.string.home_picker_hermes_connected) else null,
                selected = true,
                available = true,
                onClick = onDismiss,
                modifier = Modifier.testTag(HomeTags.PICKER_HERMES)
            )
            HarnessRow(
                initial = "A",
                name = stringResource(R.string.home_picker_auto),
                detail = stringResource(R.string.home_picker_auto_text),
                selected = false,
                available = false,
                onClick = {},
                modifier = Modifier.testTag(HomeTags.PICKER_PREVIEW_PREFIX + "auto")
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PreviewHarnesses.forEach { harness ->
                HarnessRow(
                    initial = harness.initial,
                    name = harness.name,
                    detail = stringResource(R.string.home_picker_not_available),
                    selected = false,
                    available = false,
                    onClick = {},
                    modifier = Modifier.testTag(HomeTags.PICKER_PREVIEW_PREFIX + harness.key)
                )
            }
        }
    }
}

@Composable
private fun HarnessRow(
    initial: String,
    name: String,
    detail: String?,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val preview = stringResource(R.string.home_preview_badge)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(background)
            .then(
                if (available) {
                    Modifier.clickable(role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (!available) disabled()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(if (available) ExpressiveShape.Cookie.shape else RoundedCornerShape(50))
                .background(
                    if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                )
        ) {
            Text(
                text = initial,
                fontWeight = FontWeight.Bold,
                color = if (available) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!available) PreviewBadge(text = preview)
    }
}
