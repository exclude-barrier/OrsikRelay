package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes

/** Test tags of the composer dock (Home keeps them as `HomeTags.COMPOSER_*`). */
object ComposerDockTags {
    const val INPUT = "home_composer_input"
    const val SEND = "home_composer_send"
    const val ATTACH = "home_composer_attach"
    const val AGENT = "home_composer_agent"
}

/** Presentation-only suggestions. Selecting inserts text, never submits it. */
/** [text] is inserted on selection; [label] and [detail] are only shown. */
data class ComposerSuggestion(
    val text: String,
    val enabled: Boolean = true,
    val label: String = text,
    val detail: String? = null
)

/**
 * The fixed bottom area of design v2: card surface with a hairline on top
 * and 24 dp top corners. The navigation bar below uses the same colour, so
 * both read as one block.
 */
@Composable
fun BottomDockArea(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.bottomArea)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind {
                // Hairline along the rounded top edge only.
                val stroke = 1.dp.toPx()
                val radius = 24.dp.toPx()
                drawLine(line, Offset(radius, stroke / 2), Offset(size.width - radius, stroke / 2), stroke)
                drawArc(line, 180f, 90f, false, Offset(stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(line, 270f, 90f, false, Offset(size.width - radius * 2 - stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content
    )
}

/**
 * The pill input of design v2: optional agent avatar ([agentSelector]),
 * the text field, an attach action and the round send button. [leading]
 * and [trailing] let the chat add its own controls without a second look.
 */
@Composable
fun AgentComposerDock(
    draft: String,
    onDraftChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    agentSelector: Boolean = true,
    onOpenAgentPicker: () -> Unit = {},
    attachEnabled: Boolean = enabled,
    attachBusy: Boolean = false,
    onAttach: (() -> Unit)? = null,
    attachLabel: String = stringResource(R.string.home_composer_attach),
    sendLabel: String = stringResource(R.string.home_composer_send),
    maxLines: Int = 4,
    inputTestTag: String = ComposerDockTags.INPUT,
    sendTestTag: String = ComposerDockTags.SEND,
    attachTestTag: String = ComposerDockTags.ATTACH,
    above: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    suggestions: List<ComposerSuggestion> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    suggestionMaxHeight: Dp = 192.dp
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    // External replacements (command selection, restore, clear) put the cursor
    // at the end. Ordinary typing/selection retains the IME's exact state.
    val displayedValue = if (fieldValue.text == draft) fieldValue else TextFieldValue(draft, TextRange(draft.length))
    SideEffect { fieldValue = displayedValue }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrsikShapes.dock)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, OrsikShapes.dock)
            .padding(5.dp)
    ) {
        above?.invoke(this)
        if (suggestions.isNotEmpty()) {
            Column(Modifier.heightIn(max = suggestionMaxHeight).verticalScroll(rememberScrollState()).testTag("slash_menu")) {
                suggestions.forEach { suggestion ->
                    SuggestionRow(suggestion) { onSuggestion(suggestion.text); focusRequester.requestFocus() }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (agentSelector) {
                AgentAvatar(enabled = enabled, onClick = onOpenAgentPicker, modifier = Modifier.testTag(ComposerDockTags.AGENT))
            }
            val textColor = MaterialTheme.colorScheme.onSurface
            BasicTextField(
                value = displayedValue,
                onValueChange = { value ->
                    fieldValue = value
                    if (value.text != draft) onDraftChange(value.text)
                },
                enabled = enabled,
                maxLines = maxLines,
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 44.dp)) {
                        // The hint stays composed (invisible once there is
                        // text) so it keeps labelling the field for screen
                        // readers, like a Material text-field label.
                        Text(
                            text = hint,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(if (draft.isEmpty()) 1f else 0f)
                        )
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(horizontal = if (agentSelector) 8.dp else 14.dp)
                    .testTag(inputTestTag)
            )
            if (onAttach != null) {
                IconButton(
                    onClick = onAttach,
                    enabled = attachEnabled && !attachBusy,
                    modifier = Modifier.testTag(attachTestTag)
                ) {
                    if (attachBusy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            OrsikIcons.Plus,
                            contentDescription = attachLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                SendButton(enabled = canSend, sending = sending, label = sendLabel, testTag = sendTestTag, onClick = onSend)
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        AgentMark(size = 44)
    }
}

/** Hermes' "H" in its sunny badge (composer avatar, chat replies). */
@Composable
fun AgentMark(modifier: Modifier = Modifier, size: Int = 30) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(ExpressiveShape.Sunny.shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
    ) {
        Text(
            text = "H",
            fontSize = (size * 0.37f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** The round cookie send button; it springs on press. */
@Composable
fun SendButton(enabled: Boolean, sending: Boolean, label: String, testTag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sendPress"
    )
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .clip(ExpressiveShape.Cookie.shape)
            .background(container)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = label }
            .testTag(testTag)
    ) {
        if (sending) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(20.dp))
        } else {
            Icon(OrsikIcons.ArrowUp, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: ComposerSuggestion, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(OrsikShapes.dock)
            .clickable(enabled = suggestion.enabled, role = Role.Button, onClick = onClick)
            .alpha(if (suggestion.enabled) 1f else 0.5f)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("slash:${suggestion.text}")
    ) {
        Text(
            suggestion.label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        suggestion.detail?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
