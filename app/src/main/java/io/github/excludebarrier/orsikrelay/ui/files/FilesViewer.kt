package io.github.excludebarrier.orsikrelay.ui.files

import androidx.compose.ui.platform.LocalConfiguration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.parentPath
import io.github.excludebarrier.orsikrelay.domain.files.serverPath
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage
import io.github.excludebarrier.orsikrelay.ui.components.ConnectedButtonGroup
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Text beyond this is not shown (the download still has all of it). */
private const val MAX_TEXT_CHARS = 512 * 1024
private const val MAX_IMAGE_EDGE = 2048
private const val MAX_ZOOM = 5f

private enum class TextMode { Preview, Source }

/** Preview of one file: text, image or "no preview", with save, share and the action sheet. */
@Composable
internal fun FileViewer(
    profileId: String,
    source: FilesSource,
    path: String,
    root: String?,
    sizeBytes: Long?,
    modifiedAtSeconds: Long?,
    hermes: FilesHermes?,
    onClose: () -> Unit,
    ioDispatcher: CoroutineDispatcher
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf(false) }
    val content by produceState<FilesResult<FileContent>?>(null, profileId, path, reload) {
        value = null
        value = withContext(ioDispatcher) { source.read(profileId, path) }
    }
    val name = path.substringAfterLast('/')
    val serverFile = serverPath(root, path)
    val folder = "/" + parentPath(path).orEmpty()
    val ready = (content as? FilesResult.Ready)?.value
    val entry = FileEntry(name, FileKind.File, sizeBytes ?: ready?.bytes?.size?.toLong(), modifiedAtSeconds)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = ready?.bytes
        if (uri != null && bytes != null) {
            scope.launch {
                notice = if (withContext(ioDispatcher) { writeToUri(context, uri, bytes) }) R.string.files_saved else R.string.files_save_failed
            }
        }
    }
    val download: (() -> Unit)? = ready?.let { { save.launch(name) } }
    val share: (() -> Unit)? = ready?.let { file ->
        { if (!shareBytes(context, name, file.bytes, mimeOf(file))) notice = R.string.files_share_failed }
    }
    val meta = listOfNotNull(
        folder,
        entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        if (ready?.kind == FileContentKind.Text) "UTF-8" else null,
        modifiedAtSeconds?.let { modifiedLabel(it) }
    ).joinToString(" · ")
    val image = ready?.kind == FileContentKind.Image

    Box(
        Modifier
            .fillMaxSize()
            .then(if (image) Modifier.background(Color.Black) else Modifier)
            .imePadding()
            .testTag(FilesTags.VIEWER)
    ) {
        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(
                title = name,
                subtitle = if (image) null else meta,
                dark = image,
                onClose = onClose,
                onDownload = download,
                onShare = share,
                onMore = if (image) null else ({ sheet = true })
            )
            notice?.let { message ->
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (image) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(FilesTags.NOTICE)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val result = content) {
                    null -> WorkingGlyph(Modifier.align(Alignment.Center), size = 40.dp)
                    is FilesResult.Ready -> when (result.value.kind) {
                        FileContentKind.Text -> TextPreview(name, result.value.bytes)
                        FileContentKind.Image -> ImagePreview(result.value.bytes)
                        FileContentKind.Binary -> BinaryPreview(result.value.bytes.size.toLong(), serverFile, download, share)
                    }
                    else -> Box(Modifier.padding(16.dp)) { FilesProblem(result, onRetry = { reload++ }) }
                }
            }
            if (image && ready != null) ImageInfo(ready.bytes, entry)
            if (hermes != null) {
                AskComposer(
                    hint = stringResource(R.string.files_ask_hint, name),
                    serverPath = serverFile,
                    hermes = hermes,
                    onNotice = { notice = it },
                    modifier = if (image) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier
                )
            }
        }
    }
    if (sheet) {
        FileActionSheet(
            type = fileType(entry),
            title = name,
            meta = entryMeta(entry),
            actions = listOfNotNull(
                download?.let { act -> FileAction(OrsikIcons.Download, stringResource(R.string.files_download), testTag = FilesTags.ACTION_DOWNLOAD) { sheet = false; act() } },
                share?.let { act -> FileAction(OrsikIcons.Share, stringResource(R.string.files_share), testTag = FilesTags.ACTION_SHARE) { sheet = false; act() } },
                FileAction(OrsikIcons.Link, stringResource(R.string.files_copy_path), serverFile, FilesTags.ACTION_COPY) {
                    sheet = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(sensitivePlainText(serverFile))) }
                    notice = R.string.files_path_copied
                },
                sessionAction(hermes, name, serverFile, scope, onNotice = { notice = it }) { sheet = false },
                FileAction(OrsikIcons.Pencil, stringResource(R.string.files_rename), testTag = FilesTags.ACTION_RENAME, onClick = null),
                FileAction(OrsikIcons.Trash, stringResource(R.string.files_delete), testTag = FilesTags.ACTION_DELETE, onClick = null)
            ),
            onDismiss = { sheet = false }
        )
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String?,
    dark: Boolean,
    onClose: () -> Unit,
    onDownload: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onMore: (() -> Unit)?
) {
    val tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (dark) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        IconButton(
            onClick = onClose,
            colors = if (dark) IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f)) else IconButtonDefaults.iconButtonColors(),
            modifier = Modifier.testTag("files_viewer_back")
        ) {
            Icon(
                if (dark) OrsikIcons.Close else OrsikIcons.ArrowBack,
                contentDescription = stringResource(R.string.files_close_preview),
                tint = tint
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        onDownload?.let {
            IconButton(onClick = it, modifier = Modifier.testTag(FilesTags.DOWNLOAD)) {
                Icon(OrsikIcons.Download, contentDescription = stringResource(R.string.files_download), tint = tint)
            }
        }
        onShare?.let {
            IconButton(onClick = it, modifier = Modifier.testTag(FilesTags.SHARE)) {
                Icon(OrsikIcons.Share, contentDescription = stringResource(R.string.files_share), tint = tint)
            }
        }
        onMore?.let {
            IconButton(onClick = it, modifier = Modifier.testTag(FilesTags.VIEWER_MORE)) {
                Icon(OrsikIcons.MoreVert, contentDescription = stringResource(R.string.files_more_file, title), tint = tint)
            }
        }
    }
}

@Composable
private fun TextPreview(name: String, bytes: ByteArray) {
    val text = remember(bytes) {
        val decoded = bytes.toString(Charsets.UTF_8)
        if (decoded.length > MAX_TEXT_CHARS) decoded.take(MAX_TEXT_CHARS) else decoded
    }
    val markdown = isMarkdown(name)
    var mode by rememberSaveable(name) { mutableStateOf(if (markdown) TextMode.Preview else TextMode.Source) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (markdown) {
            ConnectedButtonGroup(
                options = TextMode.entries,
                selected = mode,
                label = { stringResource(if (it == TextMode.Preview) R.string.files_mode_preview else R.string.files_mode_source) },
                onSelect = { mode = it },
                testTag = { FilesTags.MODE_PREFIX + it.name.lowercase() },
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        Box(Modifier.weight(1f, fill = false)) {
            if (markdown && mode == TextMode.Preview) {
                SelectionContainer {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag(FilesTags.MARKDOWN)) {
                        AssistantMarkdownMessage(content = text)
                    }
                }
            } else {
                SourceView(text)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
        ) {
            SoonBadge(stringResource(R.string.files_read_only_badge))
            Text(stringResource(R.string.files_read_only_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Numbered lines in Roboto Mono; long lines wrap under their own number. */
@Composable
private fun SourceView(text: String) {
    val lines = remember(text) { text.removeSuffix("\n").split('\n') }
    val numberWidth = remember(lines.size) { lines.size.toString().length }
    SelectionContainer {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 12.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .outlinedCard(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.outlineVariant)
                .testTag(FilesTags.TEXT)
        ) {
            itemsIndexed(lines) { index, line ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    Text(
                        text = (index + 1).toString().padStart(numberWidth),
                        fontFamily = OrsikMono,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                    Text(text = line, fontFamily = OrsikMono, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ImagePreview(bytes: ByteArray) {
    val bitmap = remember(bytes) { decodeBounded(bytes) }
    if (bitmap == null) {
        Text(
            stringResource(R.string.files_image_unreadable),
            color = Color.White,
            modifier = Modifier.align(Alignment.Center).padding(24.dp)
        )
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val description = stringResource(R.string.files_image_description)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .testTag(FilesTags.IMAGE)
    )
}

@Composable
private fun ImageInfo(bytes: ByteArray, entry: FileEntry) {
    val context = LocalContext.current
    val size = remember(bytes) { imageSize(bytes) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        InfoCell(stringResource(R.string.files_info_size), entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "–", Modifier.weight(1f))
        InfoCell(stringResource(R.string.files_info_dimensions), size?.let { "${it.first} × ${it.second}" } ?: "–", Modifier.weight(1f))
        InfoCell(stringResource(R.string.files_info_modified), entry.modifiedAtSeconds?.let { modifiedLabel(it) } ?: "–", Modifier.weight(1f))
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BinaryPreview(byteCount: Long, serverFile: String, onDownload: (() -> Unit)?, onShare: (() -> Unit)?) {
    val context = LocalContext.current
    val bytes = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(byteCount)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(FilesTags.BINARY)
    ) {
        Spacer(Modifier.height(24.dp))
        FileTypeIcon(FileType.Binary, size = 96)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.files_no_preview_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.files_no_preview_text, "application/octet-stream"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
            InfoRow(stringResource(R.string.files_info_type), "application/octet-stream")
            InfoRow(stringResource(R.string.files_info_size), stringResource(R.string.files_bytes, bytes))
            InfoRow(stringResource(R.string.files_info_path), serverFile, mono = true)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onDownload?.invoke() }, enabled = onDownload != null, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(OrsikIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.files_download_short))
            }
            OutlinedButton(onClick = { onShare?.invoke() }, enabled = onShare != null, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(OrsikIcons.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.files_share))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    if (mono) {
        // A path is long: label above, value below.
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = OrsikMono)
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) OrsikMono else null,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 240.dp)
        )
    }
}

/** Decodes at most [MAX_IMAGE_EDGE] px on the long edge; `null` for a broken image. */
private fun decodeBounded(bytes: ByteArray): Bitmap? {
    val (width, height) = imageSize(bytes) ?: return null
    var sample = 1
    while (maxOf(width, height) / sample > MAX_IMAGE_EDGE) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}
