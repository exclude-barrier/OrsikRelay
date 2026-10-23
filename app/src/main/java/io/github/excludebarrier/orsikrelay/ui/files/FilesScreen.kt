package io.github.excludebarrier.orsikrelay.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.childPath
import io.github.excludebarrier.orsikrelay.domain.files.parentPath
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.GroupedListItem
import io.github.excludebarrier.orsikrelay.ui.components.ListIconBox
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLoadingIndicator
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object FilesTags {
    const val SCREEN = "files_screen"
    const val ENTRY_PREFIX = "files_entry_"
    const val VIEWER = "files_viewer"
    const val TEXT = "files_text"
    const val IMAGE = "files_image"
    const val DOWNLOAD = "files_download"
    const val RETRY = "files_retry"
}

/** Text beyond this is not shown (the download still has all of it). */
private const val MAX_TEXT_CHARS = 512 * 1024
private const val MAX_IMAGE_EDGE = 2048

/**
 * Read-only browser of the server folder `BRIDGE_FILES_ROOT`: folders,
 * a text or image preview, and "save to the phone" through the system file
 * picker. Back goes up one folder (or closes the preview) before it leaves.
 */
@Composable
internal fun FilesRoute(
    profileId: String,
    source: FilesSource,
    startPath: String,
    onBack: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var path by rememberSaveable { mutableStateOf(startPath) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val file = openFile
    if (file != null) {
        BackHandler { openFile = null }
        FileViewer(profileId, source, file, onClose = { openFile = null }, ioDispatcher = ioDispatcher)
        return
    }
    val up = parentPath(path)
    BackHandler(enabled = up != null && path != startPath) { path = up.orEmpty() }

    val listing by produceState<FilesResult<FileListing>?>(null, profileId, path, reload) {
        value = null
        value = withContext(ioDispatcher) { source.list(profileId, path) }
    }
    Column(Modifier.fillMaxSize().testTag(FilesTags.SCREEN)) {
        OrsikLargeHeader(
            title = path.substringAfterLast('/').ifEmpty { stringResource(R.string.files_title) },
            onBack = { if (up != null && path != startPath) path = up else onBack() },
            backTestTag = "files_back"
        )
        if (path.isNotEmpty()) {
            Text(
                text = "/$path",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val result = listing) {
                null -> OrsikLoadingIndicator(Modifier.align(Alignment.Center))
                is FilesResult.Ready -> FileList(result.value, onOpen = { entry ->
                    when (entry.kind) {
                        FileKind.Directory -> path = childPath(path, entry.name)
                        FileKind.File -> openFile = childPath(path, entry.name)
                        FileKind.Other -> Unit
                    }
                })
                else -> FilesProblem(result, onRetry = { reload++ })
            }
        }
    }
}

@Composable
private fun FileList(listing: FileListing, onOpen: (FileEntry) -> Unit) {
    val context = LocalContext.current
    if (listing.entries.isEmpty()) {
        Text(stringResource(R.string.files_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        itemsIndexed(listing.entries, key = { _, entry -> entry.name }) { index, entry ->
            val position = groupPosition(index, listing.entries.size)
            GroupedListItem(
                position = position,
                title = entry.name,
                supporting = describe(context, entry),
                onClick = if (entry.kind == FileKind.Other) null else ({ onOpen(entry) }),
                leading = {
                    ListIconBox(if (entry.kind == FileKind.Directory) OrsikIcons.Folder else OrsikIcons.File)
                },
                modifier = Modifier.testTag(FilesTags.ENTRY_PREFIX + entry.name)
            )
        }
        if (listing.truncated) {
            item {
                Text(
                    stringResource(R.string.files_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

private fun describe(context: Context, entry: FileEntry): String {
    val date = entry.modifiedAtSeconds?.let {
        DateUtils.formatDateTime(
            context, it * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
    }
    val kind = when (entry.kind) {
        FileKind.Directory -> context.getString(R.string.files_kind_folder)
        FileKind.File -> entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
        FileKind.Other -> context.getString(R.string.files_kind_other)
    }
    return listOfNotNull(kind, date).joinToString(" · ")
}

@Composable
internal fun FilesProblem(result: FilesResult<*>, onRetry: () -> Unit) {
    val (title, text) = when (result) {
        FilesResult.Unsupported -> R.string.files_unsupported_title to R.string.files_unsupported_text
        FilesResult.NotFound -> R.string.files_not_found to null
        FilesResult.Refused -> R.string.files_refused to null
        FilesResult.TooLarge -> R.string.files_too_large to null
        else -> R.string.files_failed to null
    }
    NoticeCard(
        tone = if (result == FilesResult.Unsupported) NoticeTone.Info else NoticeTone.Error,
        title = stringResource(title),
        text = text?.let { stringResource(it) },
        actions = if (result == FilesResult.Failed) {
            listOf(NoticeAction(stringResource(R.string.files_retry), onRetry, testTag = FilesTags.RETRY))
        } else {
            emptyList()
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun FileViewer(
    profileId: String,
    source: FilesSource,
    path: String,
    onClose: () -> Unit,
    ioDispatcher: CoroutineDispatcher
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val content by produceState<FilesResult<FileContent>?>(null, profileId, path, reload) {
        value = null
        value = withContext(ioDispatcher) { source.read(profileId, path) }
    }
    val name = path.substringAfterLast('/')
    val ready = (content as? FilesResult.Ready)?.value
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = ready?.bytes
        if (uri != null && bytes != null) {
            scope.launch { saved = withContext(ioDispatcher) { write(context, uri, bytes) } }
        }
    }
    Column(Modifier.fillMaxSize().testTag(FilesTags.VIEWER)) {
        OrsikLargeHeader(title = name, onBack = onClose, backTestTag = "files_viewer_back") {
            if (ready != null) {
                IconButton(onClick = { save.launch(name) }, modifier = Modifier.testTag(FilesTags.DOWNLOAD)) {
                    Icon(OrsikIcons.Download, contentDescription = stringResource(R.string.files_download))
                }
            }
        }
        saved?.let { ok ->
            Text(
                stringResource(if (ok) R.string.files_saved else R.string.files_save_failed),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            when (val result = content) {
                null -> OrsikLoadingIndicator(Modifier.align(Alignment.Center))
                is FilesResult.Ready -> FilePreview(result.value)
                else -> FilesProblem(result, onRetry = { reload++ })
            }
        }
    }
}

@Composable
private fun FilePreview(content: FileContent) {
    when (content.kind) {
        FileContentKind.Text -> {
            val text = remember(content) {
                val decoded = content.bytes.toString(Charsets.UTF_8)
                if (decoded.length > MAX_TEXT_CHARS) decoded.take(MAX_TEXT_CHARS) else decoded
            }
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .testTag(FilesTags.TEXT)
                )
            }
        }
        FileContentKind.Image -> {
            val bitmap = remember(content) { decodeBounded(content.bytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().testTag(FilesTags.IMAGE)
                )
            } else {
                Text(stringResource(R.string.files_no_preview), style = MaterialTheme.typography.bodyMedium)
            }
        }
        FileContentKind.Binary ->
            Text(stringResource(R.string.files_no_preview), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Decodes at most [MAX_IMAGE_EDGE] px on the long edge; `null` for a broken image. */
private fun decodeBounded(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun write(context: Context, uri: Uri, bytes: ByteArray): Boolean = try {
    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
} catch (_: Exception) {
    false
}
