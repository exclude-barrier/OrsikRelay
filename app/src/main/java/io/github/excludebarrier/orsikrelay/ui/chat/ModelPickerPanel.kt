package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.chat.ModelPickerRow
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes

/** Everything the `/model` list shows; built by the chat screen from its state. */
internal data class ModelPickerUi(
    val query: String,
    val rows: List<ModelPickerRow>,
    val highlight: Int,
    val loading: Boolean,
    val failed: Boolean,
    val loaded: Boolean,
    val total: Int,
    val providers: Int,
    /** A turn runs: the rows are dimmed and cannot be chosen. */
    val turnActive: Boolean,
    val busy: Boolean
)

internal const val TAG_MODEL_PICKER = "model_picker"
internal const val TAG_MODEL_PICKER_STATUS = "model_picker_status"
internal const val TAG_MODEL_PICKER_LIST = "model_picker_list"
internal fun modelRowTag(qualified: String) = "model:$qualified"

/**
 * The `/model` list above the field: a count line, "current & recent",
 * then the other models by provider (or the filter hits). Tapping a row
 * locks that model at once. At most [maxHeight] high; it scrolls inside
 * and keeps the keyboard-marked row in view.
 */
@Composable
internal fun ModelPickerPanel(ui: ModelPickerUi, maxHeight: Dp, onPick: (ModelPickerRow.Item) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(TAG_MODEL_PICKER)) {
        val status = when {
            ui.loading -> stringResource(R.string.slash_models_loading)
            ui.failed -> stringResource(R.string.slash_models_failed)
            ui.loaded && ui.total == 0 -> stringResource(R.string.slash_models_empty)
            ui.loaded && ui.rows.isEmpty() -> stringResource(R.string.slash_models_no_match, ui.query.trim())
            else -> null
        }
        if (status != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag(TAG_MODEL_PICKER_STATUS)
            ) {
                if (ui.loading) {
                    WorkingGlyph(size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    status,
                    fontSize = 14.sp,
                    color = if (ui.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        if (ui.rows.isEmpty()) return@Column
        val caption = if (ui.turnActive) {
            stringResource(R.string.slash_models_locked_run)
        } else if (ui.query.isBlank()) {
            stringResource(
                R.string.slash_models_header,
                pluralStringResource(R.plurals.slash_models_count_models, ui.total, ui.total),
                pluralStringResource(R.plurals.slash_models_count_providers, ui.providers, ui.providers)
            )
        } else {
            null
        }
        caption?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        val listState = rememberLazyListState()
        val markedIndex = ui.rows.indexOfNthItem(ui.highlight)
        LaunchedEffect(markedIndex) {
            if (markedIndex >= 0) {
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.none { it.index == markedIndex && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
                    listState.animateScrollToItem(markedIndex)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = maxHeight).testTag(TAG_MODEL_PICKER_LIST)) {
            var itemIndex = -1
            val numbered = ui.rows.map { row -> if (row is ModelPickerRow.Item) row to ++itemIndex else row to -1 }
            itemsIndexed(numbered, key = { index, (row, _) -> rowKey(index, row) }) { _, (row, position) ->
                when (row) {
                    ModelPickerRow.RecentHeader -> SectionTitle(stringResource(R.string.slash_models_recent))
                    is ModelPickerRow.ProviderHeader -> SectionTitle(row.provider)
                    is ModelPickerRow.Item -> ModelRow(
                        row = row,
                        marked = position == ui.highlight,
                        enabled = !ui.turnActive && !ui.busy,
                        onClick = { onPick(row) }
                    )
                }
            }
        }
    }
}

private fun rowKey(index: Int, row: ModelPickerRow): String = when (row) {
    ModelPickerRow.RecentHeader -> "recent"
    is ModelPickerRow.ProviderHeader -> "provider:${row.provider}"
    // A model may show twice (recent and its provider group).
    is ModelPickerRow.Item -> "item:$index:${row.option.qualified}"
}

/** The row index of the [n]-th selectable model, or -1. */
private fun List<ModelPickerRow>.indexOfNthItem(n: Int): Int {
    var seen = -1
    forEachIndexed { index, row ->
        if (row is ModelPickerRow.Item && ++seen == n) return index
    }
    return -1
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun ModelRow(row: ModelPickerRow.Item, marked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val name = buildAnnotatedString {
        val model = row.option.model
        val match = row.match
        if (match == null) {
            append(model)
        } else {
            append(model.substring(0, match.first))
            withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) {
                append(model.substring(match.first, match.last + 1))
            }
            append(model.substring(match.last + 1))
        }
    }
    val currentLabel = stringResource(R.string.slash_model_current_marker)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(OrsikShapes.dock)
            .background(if (marked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                selected = marked
                if (row.current) contentDescription = "${row.option.model}, ${row.option.provider}, $currentLabel"
            }
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag(modelRowTag(row.option.qualified))
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                row.option.provider,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.current) {
            Icon(OrsikIcons.Check, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
        }
    }
}
