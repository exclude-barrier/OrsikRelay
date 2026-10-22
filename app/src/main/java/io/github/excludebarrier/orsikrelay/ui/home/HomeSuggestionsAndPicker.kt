package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.unit.sp
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.PreviewBadge


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
                label = { Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium) },
                shape = OrsikShapes.chip,
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = enabled,
                    borderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag(HomeTags.SUGGESTION_PREFIX + key)
            )
        }
    }
}

/** A future agent next to Hermes; shown as "Bald", not selectable yet. */
internal class PreviewHarness(val key: String, val name: String, val initial: String)

/**
 * The one list of future agents, used by the Home picker and the Agents
 * filter so both always show the same agents under the same names.
 */
internal val PreviewHarnesses = listOf(
    PreviewHarness("claude_code", "Claude Code", "C"),
    PreviewHarness("codex", "Codex", "X"),
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
