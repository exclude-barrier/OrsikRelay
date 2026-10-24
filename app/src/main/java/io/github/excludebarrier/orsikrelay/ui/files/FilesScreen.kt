package io.github.excludebarrier.orsikrelay.ui.files

import androidx.compose.ui.res.pluralStringResource
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.excludebarrier.orsikrelay.domain.files.serverPath
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.components.AgentComposerDock
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.GroupPosition
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
import io.github.excludebarrier.orsikrelay.ui.components.shape
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object FilesTags {
    const val SCREEN = "files_screen"
    const val ENTRY_PREFIX = "files_entry_"
    const val MORE_PREFIX = "files_more_"
    const val CRUMB_PREFIX = "files_crumb_"
    const val SEARCH = "files_search"
    const val SORT = "files_sort"
    const val SORT_OPTION_PREFIX = "files_sort_"
    const val LAYOUT = "files_layout"
    const val GRID = "files_grid"
    const val FOLDER_MENU = "files_folder_menu"
    const val SKELETON = "files_skeleton"
    const val EMPTY = "files_empty"
    const val REFUSED = "files_refused"
    const val REFUSED_UP = "files_refused_up"
    const val RETRY = "files_retry"
    const val COMPOSER = "files_composer"
    const val VIEWER = "files_viewer"
    const val TEXT = "files_text"
    const val MARKDOWN = "files_markdown"
    const val MODE_PREFIX = "files_mode_"
    const val IMAGE = "files_image"
    const val BINARY = "files_binary"
    const val DOWNLOAD = "files_download"
    const val SHARE = "files_share"
    const val VIEWER_MORE = "files_viewer_more"
    const val SHEET = "files_sheet"
    const val ACTION_DOWNLOAD = "files_action_download"
    const val ACTION_SHARE = "files_action_share"
    const val ACTION_COPY = "files_action_copy"
    const val ACTION_SESSION = "files_action_session"
    const val ACTION_RENAME = "files_action_rename"
    const val ACTION_DELETE = "files_action_delete"
    const val NOTICE = "files_notice"
}

internal enum class FilesAskResult { Opened, Busy, Failed }

/**
 * What the file screens may hand to Hermes: a question about a path (a new
 * session whose first message carries the path) and a new session whose
 * composer holds the path, sent only by the user. `null` hides both.
 */
internal class FilesHermes(
    val ask: suspend (serverPath: String, question: String) -> FilesAskResult,
    val openInSession: suspend (title: String, serverPath: String) -> Boolean
)

/**
 * Read-only browser of the server folder `BRIDGE_FILES_ROOT` (design v2):
 * breadcrumbs, search, sort, list or grid, previews for text (Markdown
 * rendered or as source), images (zoom) and other files, and an action
 * sheet. Back goes up one folder (or closes the preview) before it leaves.
 */
@Composable
internal fun FilesRoute(
    profileId: String,
    source: FilesSource,
    startPath: String,
    onBack: () -> Unit,
    hermes: FilesHermes? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var path by rememberSaveable { mutableStateOf(startPath) }
    var root by rememberSaveable { mutableStateOf<String?>(null) }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var openSize by rememberSaveable { mutableStateOf<Long?>(null) }
    var openModified by rememberSaveable { mutableStateOf<Long?>(null) }

    val opened = openName
    if (opened != null) {
        BackHandler { openName = null }
        FileViewer(
            profileId = profileId,
            source = source,
            path = childPath(path, opened),
            root = root,
            sizeBytes = openSize,
            modifiedAtSeconds = openModified,
            hermes = hermes,
            onClose = { openName = null },
            ioDispatcher = ioDispatcher
        )
        return
    }
    FolderBrowser(
        profileId = profileId,
        source = source,
        path = path,
        startPath = startPath,
        onPath = { path = it },
        root = root,
        onRoot = { root = it },
        onOpen = { entry ->
            openName = entry.name
            openSize = entry.sizeBytes
            openModified = entry.modifiedAtSeconds
        },
        onBack = onBack,
        hermes = hermes,
        ioDispatcher = ioDispatcher
    )
}

@Composable
private fun FolderBrowser(
    profileId: String,
    source: FilesSource,
    path: String,
    startPath: String,
    onPath: (String) -> Unit,
    root: String?,
    onRoot: (String?) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onBack: () -> Unit,
    hermes: FilesHermes?,
    ioDispatcher: CoroutineDispatcher
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable(path) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FileSort.Name) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<FileEntry?>(null) }
    var folderSheet by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }

    val up = parentPath(path)
    // Back walks up only inside what this visit opened (a project starts deeper).
    val canGoUp = up != null && path != startPath
    BackHandler(enabled = canGoUp) { onPath(up.orEmpty()) }

    val listing by produceState<FilesResult<FileListing>?>(null, profileId, path, reload) {
        value = null
        val result = withContext(ioDispatcher) { source.list(profileId, path) }
        if (result is FilesResult.Ready) onRoot(result.value.root)
        value = result
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingSave
        pendingSave = null
        if (uri != null && bytes != null) {
            scope.launch {
                notice = if (withContext(ioDispatcher) { writeToUri(context, uri, bytes) }) R.string.files_saved else R.string.files_save_failed
            }
        }
    }

    fun withContent(entry: FileEntry, use: (FileContent) -> Unit) {
        scope.launch {
            when (val read = withContext(ioDispatcher) { source.read(profileId, childPath(path, entry.name)) }) {
                is FilesResult.Ready -> use(read.value)
                FilesResult.TooLarge -> notice = R.string.files_too_large
                else -> notice = R.string.files_failed
            }
        }
    }

    val folderLabel = path.substringAfterLast('/').ifEmpty {
        root?.substringAfterLast('/')?.ifEmpty { null } ?: stringResource(R.string.files_title)
    }
    val displayPath = "/$path"
    Column(Modifier.fillMaxSize().imePadding().testTag(FilesTags.SCREEN)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = { if (canGoUp) onPath(up.orEmpty()) else onBack() }, modifier = Modifier.testTag("files_back")) {
                Icon(OrsikIcons.ArrowBack, contentDescription = stringResource(R.string.files_back))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { folderSheet = true }, modifier = Modifier.testTag(FilesTags.FOLDER_MENU)) {
                Icon(OrsikIcons.MoreVert, contentDescription = stringResource(R.string.files_more_folder))
            }
        }
        Text(
            text = folderLabel,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() }
        )
        Breadcrumbs(
            path = path,
            rootLabel = stringResource(R.string.files_root_crumb),
            onCrumb = { target -> if (target != path) onPath(target) },
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            SearchField(query, { query = it }, stringResource(R.string.files_search_hint, displayPath), Modifier.weight(1f))
            SortButton(sort, onSort = { sort = it })
            OutlinedIconButton(onClick = { grid = !grid }, modifier = Modifier.size(48.dp).testTag(FilesTags.LAYOUT)) {
                Icon(
                    if (grid) OrsikIcons.ListView else OrsikIcons.Grid,
                    contentDescription = stringResource(if (grid) R.string.files_layout_list else R.string.files_layout_grid)
                )
            }
        }
        notice?.let { message ->
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(FilesTags.NOTICE)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            when (val result = listing) {
                null -> FileListSkeleton(Modifier.padding(top = 8.dp))
                is FilesResult.Ready -> FolderContent(
                    listing = result.value,
                    entries = arrange(result.value.entries, sort, query),
                    query = query,
                    grid = grid,
                    onOpen = { entry ->
                        when (entry.kind) {
                            FileKind.Directory -> onPath(childPath(path, entry.name))
                            FileKind.File -> onOpen(entry)
                            FileKind.Other -> Unit
                        }
                    },
                    onMore = { sheetFor = it }
                )
                FilesResult.Refused, FilesResult.NotFound -> RefusedCard(
                    path = displayPath,
                    notFound = result == FilesResult.NotFound,
                    upLabel = up?.let { "/$it" },
                    onUp = up?.let { { onPath(it) } }
                )
                else -> FilesProblem(result, onRetry = { reload++ })
            }
        }
        if (hermes != null) {
            AskComposer(
                hint = stringResource(R.string.files_ask_hint, displayPath),
                serverPath = serverPath(root, path),
                hermes = hermes,
                onNotice = { notice = it }
            )
        }
    }

    sheetFor?.let { entry ->
        val serverFile = serverPath(root, childPath(path, entry.name))
        FileActionSheet(
            type = fileType(entry),
            title = entry.name,
            meta = entryMeta(entry),
            actions = listOfNotNull(
                FileAction(OrsikIcons.Download, stringResource(R.string.files_download), testTag = FilesTags.ACTION_DOWNLOAD, onClick = {
                    sheetFor = null
                    withContent(entry) { content ->
                        pendingSave = content.bytes
                        save.launch(entry.name)
                    }
                }),
                FileAction(OrsikIcons.Share, stringResource(R.string.files_share), testTag = FilesTags.ACTION_SHARE, onClick = {
                    sheetFor = null
                    withContent(entry) { content ->
                        if (!shareBytes(context, entry.name, content.bytes, mimeOf(content))) notice = R.string.files_share_failed
                    }
                }),
                FileAction(OrsikIcons.Link, stringResource(R.string.files_copy_path), serverFile, FilesTags.ACTION_COPY) {
                    sheetFor = null
                    scope.launch { clipboard.setClipEntry(ClipEntry(sensitivePlainText(serverFile))) }
                    notice = R.string.files_path_copied
                },
                sessionAction(hermes, entry.name, serverFile, scope, onNotice = { notice = it }) { sheetFor = null },
                FileAction(OrsikIcons.Pencil, stringResource(R.string.files_rename), testTag = FilesTags.ACTION_RENAME, onClick = null),
                FileAction(OrsikIcons.Trash, stringResource(R.string.files_delete), testTag = FilesTags.ACTION_DELETE, onClick = null)
            ),
            onDismiss = { sheetFor = null }
        )
    }
    if (folderSheet) {
        val serverFolder = serverPath(root, path)
        FileActionSheet(
            type = FileType.Folder,
            title = folderLabel,
            meta = serverFolder,
            actions = listOfNotNull(
                FileAction(OrsikIcons.Link, stringResource(R.string.files_copy_path), serverFolder, FilesTags.ACTION_COPY) {
                    folderSheet = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(sensitivePlainText(serverFolder))) }
                    notice = R.string.files_path_copied
                },
                sessionAction(hermes, folderLabel, serverFolder, scope, onNotice = { notice = it }) { folderSheet = false }
            ),
            onDismiss = { folderSheet = false }
        )
    }
}

@Composable
internal fun sessionAction(
    hermes: FilesHermes?,
    title: String,
    serverPath: String,
    scope: CoroutineScope,
    onNotice: (Int) -> Unit,
    dismiss: () -> Unit
): FileAction? {
    hermes ?: return null
    return FileAction(
        OrsikIcons.Chat,
        stringResource(R.string.files_open_in_session),
        stringResource(R.string.files_open_in_session_detail),
        FilesTags.ACTION_SESSION
    ) {
        dismiss()
        scope.launch { if (!hermes.openInSession(title, serverPath)) onNotice(R.string.files_session_failed) }
    }
}

internal fun mimeOf(content: FileContent): String = when (content.kind) {
    FileContentKind.Text -> "text/plain"
    FileContentKind.Image -> "image/*"
    FileContentKind.Binary -> "application/octet-stream"
}

internal fun Modifier.clickableButton(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)

@Composable
private fun Breadcrumbs(path: String, rootLabel: String, onCrumb: (String) -> Unit, modifier: Modifier = Modifier) {
    val crumbs = breadcrumbs(path, rootLabel)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.first }) { index, (target, label) ->
            val current = index == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Icon(
                        OrsikIcons.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp).size(18.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = if (current) null else OrsikMono,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .then(if (current) Modifier else Modifier.clickableButton { onCrumb(target) })
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { if (current) selected = true }
                        .testTag(FilesTags.CRUMB_PREFIX + target)
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp)
    ) {
        Icon(OrsikIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    hint, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }.testTag(FilesTags.SEARCH)
            )
        }
    }
}

@Composable
private fun SortButton(sort: FileSort, onSort: (FileSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        FileSort.Name to stringResource(R.string.files_sort_name),
        FileSort.Date to stringResource(R.string.files_sort_date),
        FileSort.Size to stringResource(R.string.files_sort_size)
    )
    val description = stringResource(R.string.files_sort_description, labels.getValue(sort))
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = description }
                .testTag(FilesTags.SORT)
        ) {
            Icon(OrsikIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(labels.getValue(sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(option), fontWeight = if (option == sort) FontWeight.Bold else null) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                    modifier = Modifier.testTag(FilesTags.SORT_OPTION_PREFIX + option.name.lowercase())
                )
            }
        }
    }
}

@Composable
private fun FolderContent(
    listing: FileListing,
    entries: List<FileEntry>,
    query: String,
    grid: Boolean,
    onOpen: (FileEntry) -> Unit,
    onMore: (FileEntry) -> Unit
) {
    if (listing.entries.isEmpty()) {
        EmptyFolder(stringResource(R.string.files_empty_title), stringResource(R.string.files_empty))
        return
    }
    if (entries.isEmpty()) {
        EmptyFolder(stringResource(R.string.files_no_match, query.trim()), null)
        return
    }
    val folders = entries.filter { it.kind == FileKind.Directory }
    val files = entries.filter { it.kind != FileKind.Directory }
    val context = LocalContext.current
    val fileSummary = stringResource(
        R.string.files_section_summary,
        pluralStringResource(R.plurals.files_count, files.size, files.size),
        Formatter.formatShortFileSize(context, files.sumOf { it.sizeBytes ?: 0L })
    )
    val foldersTitle = stringResource(R.string.files_section_folders)
    val filesTitle = stringResource(R.string.files_section_files)
    LazyVerticalGrid(
        columns = if (grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
        verticalArrangement = Arrangement.spacedBy(if (grid) 8.dp else GroupGap),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize().then(if (grid) Modifier.testTag(FilesTags.GRID) else Modifier)
    ) {
        section("folders", foldersTitle, null, folders, grid, onOpen, onMore)
        section("files", filesTitle, fileSummary, files, grid, onOpen, onMore)
        if (listing.truncated) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.files_truncated), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

private fun LazyGridScope.section(
    key: String,
    title: String,
    summary: String?,
    entries: List<FileEntry>,
    grid: Boolean,
    onOpen: (FileEntry) -> Unit,
    onMore: (FileEntry) -> Unit
) {
    if (entries.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }, key = "header-$key") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp, end = 4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    itemsIndexed(entries, key = { _, entry -> "$key/${entry.name}" }) { index, entry ->
        if (grid) {
            FileGridCard(entry, onOpen = { onOpen(entry) }, onMore = { onMore(entry) })
        } else {
            FileRow(entry, groupPosition(index, entries.size), onOpen = { onOpen(entry) }, onMore = { onMore(entry) })
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, position: GroupPosition, onOpen: () -> Unit, onMore: () -> Unit) {
    val openable = entry.kind != FileKind.Other
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(position.shape())
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (openable) Modifier.clickableButton(onOpen) else Modifier)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag(FilesTags.ENTRY_PREFIX + entry.name)
    ) {
        FileTypeIcon(fileType(entry), size = 48)
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entryMeta(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (entry.kind) {
            FileKind.Directory -> Icon(
                OrsikIcons.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)
            )
            FileKind.File -> IconButton(onClick = onMore, modifier = Modifier.testTag(FilesTags.MORE_PREFIX + entry.name)) {
                Icon(OrsikIcons.MoreVert, contentDescription = stringResource(R.string.files_more_file, entry.name))
            }
            FileKind.Other -> Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun FileGridCard(entry: FileEntry, onOpen: () -> Unit, onMore: () -> Unit) {
    val openable = entry.kind != FileKind.Other
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (openable) Modifier.clickableButton(onOpen) else Modifier)
            .padding(14.dp)
            .testTag(FilesTags.ENTRY_PREFIX + entry.name)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FileTypeIcon(fileType(entry), size = 48)
            Spacer(Modifier.weight(1f))
            if (entry.kind == FileKind.File) {
                IconButton(onClick = onMore, modifier = Modifier.testTag(FilesTags.MORE_PREFIX + entry.name)) {
                    Icon(OrsikIcons.MoreVert, contentDescription = stringResource(R.string.files_more_file, entry.name))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(entryMeta(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun EmptyFolder(title: String, text: String?) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .dashedOutline(outline)
            .padding(vertical = 32.dp, horizontal = 20.dp)
            .testTag(FilesTags.EMPTY)
    ) {
        Icon(OrsikIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RefusedCard(path: String, notFound: Boolean, upLabel: String?, onUp: (() -> Unit)?) {
    val error = MaterialTheme.colorScheme.error
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(error.copy(alpha = 0.10f))
            .padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(FilesTags.REFUSED)
    ) {
        Icon(OrsikIcons.Lock, contentDescription = null, tint = error)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (notFound) R.string.files_not_found_title else R.string.files_refused_title, path),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = error
            )
            Text(
                stringResource(if (notFound) R.string.files_not_found else R.string.files_refused),
                style = MaterialTheme.typography.bodyMedium,
                color = error
            )
            if (onUp != null && upLabel != null) {
                Button(
                    onClick = onUp,
                    colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.padding(top = 6.dp).testTag(FilesTags.REFUSED_UP)
                ) { Text(stringResource(R.string.files_back_to, upLabel)) }
            }
        }
    }
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
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** "Hermes zu <Pfad> fragen …": a new session whose first message names the path. */
@Composable
internal fun AskComposer(
    hint: String,
    serverPath: String,
    hermes: FilesHermes,
    onNotice: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(serverPath) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AgentComposerDock(
        draft = draft,
        onDraftChange = { draft = it },
        hint = hint,
        enabled = !sending,
        canSend = draft.isNotBlank() && !sending,
        sending = sending,
        onSend = {
            val question = draft.trim()
            sending = true
            scope.launch {
                when (hermes.ask(serverPath, question)) {
                    FilesAskResult.Opened -> draft = ""
                    FilesAskResult.Busy -> onNotice(R.string.files_ask_busy)
                    FilesAskResult.Failed -> onNotice(R.string.files_ask_failed)
                }
                sending = false
            }
        },
        inputTestTag = FilesTags.COMPOSER,
        modifier = modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
