package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.em
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
            // Assistant answers are read inside a chat bubble, not as an
            // article: headings stay close to the body size instead of the
            // display-scale Material defaults.
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                text = MaterialTheme.typography.bodyLarge,
                paragraph = MaterialTheme.typography.bodyLarge,
            ),
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
    val clipboard = LocalClipboard.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = language?.takeIf { it.isNotBlank() }?.uppercase()
    val copied = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(CODE_BLOCK_CORNER)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(shape)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = shape)
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CODE_BLOCK_HEADER_HEIGHT)
                .testTag(TAG_CODE_HEADER)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Text(
                text = label ?: stringResource(R.string.chat_code_language_fallback),
                fontSize = 12.sp,
                letterSpacing = 0.06.em,
                color = muted,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
            )
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    // The touch target keeps the 48 dp minimum while the
                    // visible header stays compact: the clickable box
                    // overhangs the header edge instead of growing it.
                    .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = stringResource(R.string.chat_code_copy)) {
                        copied.value = true
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(sensitiveClip(code)))
                            delay(COPIED_FEEDBACK_MS)
                            copied.value = false
                        }
                    }
                    .testTag(TAG_CODE_COPY)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (copied.value) OrsikIcons.Check else OrsikIcons.Copy,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(if (copied.value) R.string.chat_code_copied else R.string.chat_code_copy),
                    fontSize = 12.5.sp,
                    color = muted,
                )
            }
        }
        Text(
            text = code,
            style = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

internal const val TAG_CODE_HEADER = "chatCodeHeader"
internal const val TAG_CODE_COPY = "chatCodeCopy"

private val CODE_BLOCK_HEADER_HEIGHT = 44.dp
private val CODE_BLOCK_CORNER = 16.dp
private const val COPIED_FEEDBACK_MS = 1500L

/**
 * Code from an answer can hold tokens, keys or paths, so the copy is marked
 * sensitive: Android 13+ then hides its content in the clipboard preview
 * ("Add sensitive content to the clipboard",
 * https://developer.android.com/develop/ui/views/touch-and-input/copy-paste).
 */
private fun sensitiveClip(code: String): ClipData =
    ClipData.newPlainText(null, code).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }

