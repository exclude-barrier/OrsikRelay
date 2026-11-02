package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownAlert
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.BulletHandler
import com.mikepenz.markdown.model.MarkdownAlertType
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.findAlertType
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Renders an assistant message as markdown.
 *
 * This is the UI-only rendering boundary: it knows nothing about the network,
 * the repositories, the chat view model or the SSE protocol. It parses the raw
 * assistant text with the multiplatform markdown renderer and renders the
 * result as Compose. Parsing is retained across content updates
 * (`retainState = true`) so streaming tokens never blank the message, and
 * links are navigated only through the fail-closed [LinkSchemePolicy] via
 * [rememberSecureUriHandler]. A link the policy rejects is shown struck
 * through with "blocked" and opens nothing.
 *
 * [onAllowedLinkOpen] is a test seam that receives an allowed (https) URL
 * instead of launching a real intent; [onBlockedLinkTap] replaces the short
 * hint a blocked link shows. [onPasteToTerminal] offers "paste into
 * terminal" on shell code blocks (it never runs anything); null hides it.
 */
@Composable
internal fun AssistantMarkdownMessage(
    content: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onAllowedLinkOpen: ((String) -> Unit)? = null,
    onBlockedLinkTap: (() -> Unit)? = null,
    onPasteToTerminal: ((String) -> Unit)? = null,
) {
    val markdownState = rememberMarkdownState(content = content, retainState = true)
    val components = remember(onPasteToTerminal) {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    block = { code, language, codeStyle ->
                        OrsikCodeBlock(code = code, language = language, codeStyle = codeStyle, onPasteToTerminal = onPasteToTerminal)
                    }
                )
            },
            codeBlock = { model ->
                MarkdownCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    block = { code, language, codeStyle ->
                        OrsikCodeBlock(code = code, language = language, codeStyle = codeStyle, onPasteToTerminal = onPasteToTerminal)
                    }
                )
            },
            orderedList = { model ->
                MarkdownOrderedList(
                    content = model.content,
                    node = model.node,
                    style = model.typography.ordered,
                    depth = model.listDepth,
                    markerModifier = { Modifier.width(ORDERED_MARKER_WIDTH) }
                )
            },
            unorderedList = { model ->
                MarkdownBulletList(
                    content = model.content,
                    node = model.node,
                    style = model.typography.bullet,
                    depth = model.listDepth,
                    markerModifier = { Modifier.width(BULLET_MARKER_WIDTH) }
                )
            },
            table = { model -> OrsikTable(content = model.content, node = model.node) },
            alert = { model ->
                val type = model.node.findAlertType(model.content)
                if (type == MarkdownAlertType.WARNING) {
                    OrsikWarning(content = model.content, node = model.node)
                } else {
                    MarkdownAlert(
                        model.content,
                        model.node,
                        type ?: MarkdownAlertType.NOTE,
                        style = model.typography.alertTitle
                    )
                }
            }
        )
    }
    val context = LocalContext.current
    val blockedHint = stringResource(R.string.chat_link_blocked_hint)
    val blockedTap = onBlockedLinkTap ?: { Toast.makeText(context, blockedHint, Toast.LENGTH_SHORT).show() }
    val secureHandler = rememberSecureUriHandler(
        context = context,
        onAllowedLinkOpen = onAllowedLinkOpen,
    )
    val colors = MaterialTheme.colorScheme
    val linkStyles = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
    val annotation = LinkLook(
        link = linkStyles,
        domain = SpanStyle(color = colors.onSurfaceVariant, fontSize = 0.8.em),
        blocked = SpanStyle(color = colors.onSurfaceVariant, textDecoration = TextDecoration.LineThrough),
        blockedLabel = SpanStyle(color = colors.error, fontSize = 0.8.em),
        blockedText = stringResource(R.string.chat_link_blocked),
    )
    val annotator = remember(annotation, blockedTap) {
        markdownAnnotator { source, child -> appendSafeLink(source, child, annotation, blockedTap) }
    }
    // 16 sp on 24 sp in "Normal"; the chat text size scales the sp density.
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
    val inlineCodeBackground = colors.surfaceContainerHigh
    CompositionLocalProvider(
        LocalUriHandler provides secureHandler,
        LocalBulletListHandler provides BULLETS,
        LocalOrderedListHandler provides NUMBERS,
    ) {
        Markdown(
            markdownState = markdownState,
            colors = markdownColor(inlineCodeBackground = inlineCodeBackground),
            // Assistant answers read like a document: headings stay close
            // to the body size instead of the display-scale defaults.
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                h3 = body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
                h4 = body.copy(fontWeight = FontWeight.Bold),
                h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                text = body,
                paragraph = body,
                ordered = body.copy(fontFeatureSettings = "tnum"),
                bullet = body,
                list = body,
                quote = body.copy(color = colors.onSurfaceVariant),
                table = body,
                code = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrsikMono),
                inlineCode = body.copy(fontFamily = OrsikMono, fontSize = 0.86.em),
                textLink = linkStyles,
            ),
            padding = markdownPadding(
                block = 0.dp,
                list = 0.dp,
                listItemTop = LIST_ITEM_GAP / 2,
                listItemBottom = LIST_ITEM_GAP / 2,
                listIndent = 0.dp,
                blockQuote = PaddingValues(start = 14.dp),
            ),
            dimens = markdownDimens(blockQuoteThickness = 3.dp),
            annotator = annotator,
            extendedSpans = markdownExtendedSpans {
                remember {
                    ExtendedSpans(
                        RoundedCornerSpanPainter(
                            cornerRadius = 5.sp,
                            padding = RoundedCornerSpanPainter.TextPaddingValues(horizontal = 0.sp, vertical = 1.sp),
                            topMargin = 2.sp,
                            bottomMargin = 2.sp,
                        )
                    )
                }
            },
            components = components,
            modifier = modifier,
            success = { state, markdownComponents, successModifier ->
                // Own block spacing instead of the renderer's uniform spacer:
                // 12 dp between blocks, 18 dp above and 6 dp below a heading.
                Column(successModifier) {
                    var previous: BlockKind? = null
                    state.node.children.forEach { node ->
                        if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) return@forEach
                        val kind = if (node.type in HEADINGS) BlockKind.Heading else BlockKind.Other
                        val gap = blockGapBefore(previous, kind)
                        if (gap > 0) Spacer(Modifier.height(gap.dp))
                        MarkdownElement(node, markdownComponents, state.content, includeSpacer = false)
                        previous = kind
                    }
                }
            },
        )
    }
}

private val HEADINGS = setOf(
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
)

/** Two list levels: • on top, ◦ below (deeper levels keep ◦). */
private val BULLETS = BulletHandler { _, _, _, _, depth -> if (depth == 0) "•" else "◦" }
private val NUMBERS = BulletHandler { _, _, index, listNumber, _ -> "${listNumber + index}." }

private val LIST_ITEM_GAP = 4.dp
private val BULLET_MARKER_WIDTH = 22.dp
private val ORDERED_MARKER_WIDTH = 28.dp

/** Styles and the "blocked" label of links, resolved once per composition. */
private data class LinkLook(
    val link: TextLinkStyles,
    val domain: SpanStyle,
    val blocked: SpanStyle,
    val blockedLabel: SpanStyle,
    val blockedText: String,
)

/**
 * Inline and auto links: an allowed (https) link keeps its text and gets
 * the small domain with "↗" after it; any other target is struck through
 * with "blocked" and only shows a hint when tapped. Returns false for every
 * other node, which the renderer then handles itself.
 */
private fun AnnotatedString.Builder.appendSafeLink(
    content: String,
    child: ASTNode,
    look: LinkLook,
    onBlocked: () -> Unit,
): Boolean {
    val target: String
    val text: String
    when (child.type) {
        MarkdownElementTypes.INLINE_LINK -> {
            target = child.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.getUnescapedTextInNode(content) ?: return false
            text = child.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?.getUnescapedTextInNode(content)?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf { it.isNotBlank() } ?: target
        }
        MarkdownElementTypes.AUTOLINK -> {
            target = child.getUnescapedTextInNode(content).removePrefix("<").removeSuffix(">")
            text = target
        }
        GFMTokenTypes.GFM_AUTOLINK -> {
            if (child.parent?.type == MarkdownElementTypes.LINK_TEXT) return false
            target = child.getUnescapedTextInNode(content)
            text = target
        }
        else -> return false
    }
    if (LinkSchemePolicy.isAllowed(target)) {
        withLink(LinkAnnotation.Url(target, look.link)) { append(text) }
        val domain = linkDomain(target)
        if (domain != null && text != target) {
            withStyle(look.domain) { append(" ↗ $domain") }
        }
    } else {
        withLink(LinkAnnotation.Clickable(BLOCKED_LINK_TAG, linkInteractionListener = { onBlocked() })) {
            withStyle(look.blocked) { append(text) }
        }
        withStyle(look.blockedLabel) { append(" ⊘ ${look.blockedText}") }
    }
    return true
}

private const val BLOCKED_LINK_TAG = "orsik_blocked_link"

/**
 * `> [!WARNING]` as a notice: warning container, a 4 dp warning bar on the
 * left, the shield and "Achtung" as title, then the alert's blocks.
 */
@Composable
private fun OrsikWarning(content: String, node: ASTNode) {
    val status = OrsikTheme.statusColors
    val title = stringResource(R.string.chat_markdown_warning)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(status.warnContainer)
            .semantics(mergeDescendants = false) { contentDescription = title }
            .testTag(TAG_MARKDOWN_WARNING)
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(status.warn)
        )
        Row(modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Icon(
                OrsikIcons.Shield,
                contentDescription = null,
                tint = status.onWarnContainer,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = status.onWarnContainer
                )
                CompositionLocalProvider(LocalMarkdownColors provides markdownColor(text = status.onWarnContainer)) {
                    val components = LocalMarkdownComponents.current
                    var pastTitle = false
                    node.children.forEach { child ->
                        when {
                            !pastTitle -> if (child.type == GFMTokenTypes.ALERT_TITLE) pastTitle = true
                            child.type == MarkdownTokenTypes.EOL ||
                                child.type == MarkdownTokenTypes.WHITE_SPACE ||
                                child.type == MarkdownTokenTypes.BLOCK_QUOTE -> Unit
                            else -> MarkdownElement(child, components, content, includeSpacer = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A GFM table in its own bordered container (12 dp radius). The first
 * column stays put while the others scroll sideways; cells never wrap. The
 * header row is smaller, bold and tinted. Every row has the same fixed
 * height, so the standing column and the scrolling part stay aligned.
 */
@Composable
private fun OrsikTable(content: String, node: ASTNode) {
    val rows = remember(node) {
        node.children
            .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    val colors = MaterialTheme.colorScheme
    val headerStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)
    val density = LocalDensity.current
    val headerHeight = with(density) { headerStyle.lineHeight.toDp() } + 20.dp
    val cellHeight = with(density) { cellStyle.lineHeight.toDp() } + 24.dp
    val shape = RoundedCornerShape(12.dp)
    val line = colors.outlineVariant

    @Composable
    fun cell(row: Int, column: Int, first: Boolean) {
        val header = row == 0
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (header) headerHeight else cellHeight)
                .then(if (header) Modifier.background(colors.surfaceContainerHigh) else Modifier)
                .padding(horizontal = 14.dp)
        ) {
            val cellNode = rows[row].getOrNull(column)
            if (cellNode != null) {
                MarkdownTableBasicText(
                    content = content,
                    cell = cellNode,
                    style = (if (header) headerStyle else cellStyle).let {
                        if (first && !header) it.copy(fontWeight = FontWeight.SemiBold) else it
                    }.copy(color = if (header) colors.onSurfaceVariant else colors.onSurface),
                    maxLines = 1,
                    overflow = if (first) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
        if (row < rows.lastIndex) HorizontalDivider(color = line)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, line, shape)
            .testTag(TAG_MARKDOWN_TABLE)
    ) {
        val firstMax = maxWidth * 0.45f
        // The header tint and its rule span the whole container, also where
        // narrow columns leave space on the right.
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(colors.surfaceContainerHigh)
        )
        if (rows.size > 1) {
            Box(
                Modifier
                    .padding(top = headerHeight)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(line)
            )
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(
                modifier = Modifier
                    .widthIn(max = firstMax)
                    .width(IntrinsicSize.Max)
                    .testTag(TAG_MARKDOWN_TABLE_FIXED)
            ) {
                rows.indices.forEach { cell(it, 0, first = true) }
            }
            if (columns > 1) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(line)
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .testTag(TAG_MARKDOWN_TABLE_SCROLL)
                ) {
                    (1 until columns).forEach { column ->
                        Column(Modifier.width(IntrinsicSize.Max)) {
                            rows.indices.forEach { cell(it, column, first = false) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A self-contained code block: a monospace, horizontally scrolling body with
 * a top bar that shows the language label and a copy action. The copy action
 * copies exactly the code content — never the markdown fences or the language
 * label — and gives a brief, quiet "copied" confirmation (no toast).
 *
 * From [CODE_COLLAPSE_LINES] lines on the body is collapsed to its first
 * lines under a fade, with "show all N lines" / "collapse" below; the state
 * belongs to this block. Shell blocks (bash, sh, shell, zsh) also offer
 * "paste into terminal" when [onPasteToTerminal] is set: it hands over the
 * command line from [terminalCommandFrom] and never presses Enter.
 */
@Composable
internal fun OrsikCodeBlock(
    code: String,
    language: String?,
    codeStyle: TextStyle,
    modifier: Modifier = Modifier,
    onPasteToTerminal: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = language?.takeIf { it.isNotBlank() }?.uppercase()
    val copied = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(CODE_BLOCK_CORNER)
    val lineCount = remember(code) { code.trimEnd('\n').lines().size }
    val collapsible = lineCount >= CODE_COLLAPSE_LINES
    var expanded by rememberSaveable { mutableStateOf(false) }
    val terminalCommand = remember(code, language, onPasteToTerminal) {
        if (onPasteToTerminal != null && isShellLanguage(language)) terminalCommandFrom(code) else null
    }
    val background = MaterialTheme.colorScheme.background
    val codeTextStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = CODE_LINE_HEIGHT)
    val lineHeightDp = with(LocalDensity.current) { CODE_LINE_HEIGHT.toDp() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = shape)
            .background(background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(CODE_BLOCK_HEADER_HEIGHT)
                .testTag(TAG_CODE_HEADER)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 14.dp, end = 6.dp)
        ) {
            Text(
                text = label ?: stringResource(R.string.chat_code_language_fallback),
                fontSize = 12.sp,
                letterSpacing = 0.06.em,
                color = muted,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp)
            )
            // The actions share the rest of the header and shorten their
            // labels at a large text size instead of pushing each other out.
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (terminalCommand != null && onPasteToTerminal != null) {
                    CodeHeaderAction(
                        icon = OrsikIcons.Terminal,
                        text = stringResource(R.string.chat_code_paste_terminal),
                        color = MaterialTheme.colorScheme.primary,
                        testTag = TAG_CODE_TERMINAL,
                        onClick = { onPasteToTerminal(terminalCommand) }
                    )
                }
                CodeHeaderAction(
                    icon = if (copied.value) OrsikIcons.Check else OrsikIcons.Copy,
                    text = stringResource(if (copied.value) R.string.chat_code_copied else R.string.chat_code_copy),
                    color = muted,
                    testTag = TAG_CODE_COPY,
                    clickLabel = stringResource(R.string.chat_code_copy),
                    onClick = {
                        copied.value = true
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(sensitiveClip(code)))
                            delay(COPIED_FEEDBACK_MS)
                            copied.value = false
                        }
                    }
                )
            }
        }
        val collapsed = collapsible && !expanded
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsed) {
                        Modifier.heightIn(max = lineHeightDp * CODE_COLLAPSED_VISIBLE_LINES + CODE_BODY_VERTICAL_PADDING * 2)
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = code,
                style = codeTextStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = CODE_BODY_VERTICAL_PADDING)
                    .testTag(TAG_CODE_BODY),
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (collapsed) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(lineHeightDp * 2)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, background)))
                )
            }
        }
        if (collapsible) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .testTag(TAG_CODE_TOGGLE)
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.chat_code_collapse)
                    } else {
                        pluralStringResource(R.plurals.chat_code_show_all, lineCount, lineCount)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RowScope.CodeHeaderAction(
    icon: ImageVector,
    text: String,
    color: Color,
    testTag: String,
    onClick: () -> Unit,
    clickLabel: String = text,
) {
    Row(
        modifier = Modifier
            .weight(1f, fill = false)
            // The touch target keeps the 48 dp minimum while the visible
            // header stays compact: the clickable box overhangs the header
            // edge instead of growing it.
            .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, fontSize = 12.5.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal const val TAG_CODE_HEADER = "chatCodeHeader"
internal const val TAG_CODE_COPY = "chatCodeCopy"
internal const val TAG_CODE_BODY = "chatCodeBody"
internal const val TAG_CODE_TOGGLE = "chatCodeToggle"
internal const val TAG_CODE_TERMINAL = "chatCodeTerminal"
internal const val TAG_MARKDOWN_TABLE = "chatMarkdownTable"
internal const val TAG_MARKDOWN_TABLE_FIXED = "chatMarkdownTableFixed"
internal const val TAG_MARKDOWN_TABLE_SCROLL = "chatMarkdownTableScroll"
internal const val TAG_MARKDOWN_WARNING = "chatMarkdownWarning"

private val CODE_BLOCK_HEADER_HEIGHT = 44.dp
private val CODE_BLOCK_CORNER = 16.dp
private val CODE_BODY_VERTICAL_PADDING = 12.dp
private val CODE_LINE_HEIGHT = 20.sp
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
