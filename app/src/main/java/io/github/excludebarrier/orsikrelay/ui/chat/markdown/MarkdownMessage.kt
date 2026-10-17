package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.excludebarrier.orsikrelay.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders an assistant message as markdown.
 *
 * This is the UI-only rendering boundary: it knows nothing about the network,
 * the repositories, the chat view model or the SSE protocol. It parses the raw
 * assistant text with the multiplatform markdown renderer and renders the
 * result as Compose. Parsing is retained across content updates
 * (`retainState = true`) so streaming tokens never blank the message, and
 * links are navigated only through the fail-closed [LinkSchemePolicy] via
 * [rememberSecureUriHandler].
 *
 * [onAllowedLinkOpen] is a test seam that receives an allowed (https) URL
 * instead of launching a real intent.
 */
@Composable
internal fun AssistantMarkdownMessage(
    content: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onAllowedLinkOpen: ((String) -> Unit)? = null,
) {
    val markdownState = rememberMarkdownState(content = content, retainState = true)
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    block = { code, language, codeStyle ->
                        OrsikCodeBlock(code = code, language = language, codeStyle = codeStyle)
                    }
                )
            },
            codeBlock = { model ->
                MarkdownCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    block = { code, language, codeStyle ->
                        OrsikCodeBlock(code = code, language = language, codeStyle = codeStyle)
                    }
                )
            }
        )
    }
    val secureHandler = rememberSecureUriHandler(
        context = LocalContext.current,
        onAllowedLinkOpen = onAllowedLinkOpen,
    )
    CompositionLocalProvider(LocalUriHandler provides secureHandler) {
        Markdown(
            markdownState = markdownState,
            colors = markdownColor(),
            typography = markdownTypography(),
            components = components,
            modifier = modifier,
        )
    }
}

/**
 * A self-contained code block: a monospace, horizontally scrolling body with
 * a top bar that shows the language label and a copy action. The copy action
 * copies exactly the code content — never the markdown fences or the language
 * label — and gives a brief, quiet "copied" confirmation (no toast).
 */
@Composable
internal fun OrsikCodeBlock(
    code: String,
    language: String?,
    codeStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val onCode = MaterialTheme.colorScheme.onSurfaceVariant
    val label = language?.takeIf { it.isNotBlank() }?.uppercase()
    val copied = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label ?: stringResource(R.string.chat_code_language_fallback),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = onCode.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier
                    // Keep the copy action at the 48 dp minimum touch target
                    // even though the visible label is small.
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = stringResource(R.string.chat_code_copy)) {
                        clipboard.setText(AnnotatedString(code))
                        copied.value = true
                        scope.launch {
                            delay(COPIED_FEEDBACK_MS)
                            copied.value = false
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(if (copied.value) R.string.chat_code_copied else R.string.chat_code_copy),
                    style = MaterialTheme.typography.labelSmall,
                    color = onCode.copy(alpha = 0.85f),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp,
        )
        Text(
            text = code,
            style = codeStyle.copy(color = onCode, fontSize = 13.sp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private const val COPIED_FEEDBACK_MS = 1500L
