package io.github.excludebarrier.orsikrelay.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.files.serverPath
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
import io.github.excludebarrier.orsikrelay.ui.components.shape
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ProjectsTags {
    const val SCREEN = "projects_screen"
    const val PROJECT_PREFIX = "projects_item_"
    const val MORE_PREFIX = "projects_more_"
    const val SUMMARY = "projects_summary"
    const val SEARCH = "projects_search"
    const val SORT_RECENT = "projects_sort_recent"
    const val SORT_NAME = "projects_sort_name"
    const val ONLY_CHANGED = "projects_only_changed"
    const val LOADING = "projects_loading"
    const val EMPTY = "projects_empty"
    const val FAILED = "projects_failed"
    const val RETRY = "projects_retry"
    const val NO_MATCH = "projects_no_match"
    const val TRUNCATED = "projects_truncated"
    const val NOTICE = "projects_notice"
    const val ACTION_FILES = "projects_action_files"
    const val ACTION_ASK = "projects_action_ask"
    const val ACTION_TERMINAL = "projects_action_terminal"
    const val ACTION_COPY = "projects_action_copy"
}

/**
 * The git projects in the server folder (`GET /v1/projects`), newest
 * commit first, with search and a "changed only" filter. Tapping one opens
 * it in the file browser; ⋮ offers Hermes, the terminal and the path.
 * Read only: git commands run through Hermes or the terminal, where the
 * user starts them.
 */
@Composable
internal fun ProjectsRoute(
    profileId: String,
    source: FilesSource,
    onOpenProject: (path: String) -> Unit,
    onBack: () -> Unit,
    hermes: FilesHermes? = null,
    /** Opens the terminal with [command] in its input; never runs it. */
    onOpenTerminal: ((command: String) -> Unit)? = null,
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var reload by remember { mutableIntStateOf(0) }
    val listing by produceState<FilesResult<ProjectListing>?>(null, profileId, reload) {
        value = null
        value = withContext(ioDispatcher) { source.projects(profileId) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ProjectSort.Recent) }
    var changedOnly by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Project?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    // Outlives the sheet: an action closes it and then finishes its work.
    val scope = rememberCoroutineScope()

    val ready = (listing as? FilesResult.Ready)?.value
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag(ProjectsTags.SCREEN)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag("projects_back")) {
            Icon(OrsikIcons.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            text = stringResource(R.string.projects_title),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() }
        )
        if (ready != null && ready.projects.isNotEmpty()) {
            val changed = ready.projects.count { it.dirty == true }
            Text(
                text = pluralStringResource(R.plurals.projects_count, ready.projects.size, ready.projects.size) +
                    " · " + stringResource(R.string.projects_changed_count, changed),
                style = OrsikType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).testTag(ProjectsTags.SUMMARY)
            )
        }
        notice?.let { message ->
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ProjectsTags.NOTICE)
            )
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val result = listing) {
                null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).testTag(ProjectsTags.LOADING)
                ) {
                    WorkingGlyph(size = 40.dp)
                    Text(
                        stringResource(R.string.projects_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                is FilesResult.Ready -> ProjectList(
                    listing = result.value,
                    query = query,
                    onQuery = { query = it },
                    sort = sort,
                    onSort = { sort = it },
                    changedOnly = changedOnly,
                    onChangedOnly = { changedOnly = it },
                    nowSeconds = nowSeconds(),
                    onOpen = onOpenProject,
                    onMore = { sheetFor = it }
                )
                FilesResult.Failed -> NoticeCard(
                    tone = NoticeTone.Error,
                    title = stringResource(R.string.projects_failed),
                    actions = listOf(NoticeAction(stringResource(R.string.files_retry), { reload++ }, testTag = ProjectsTags.RETRY)),
                    modifier = Modifier.padding(top = 12.dp).testTag(ProjectsTags.FAILED)
                )
                else -> FilesProblem(result, onRetry = { reload++ })
            }
        }
    }
    sheetFor?.let { project ->
        ProjectSheet(
            profileId = profileId,
            source = source,
            project = project,
            hermes = hermes,
            onOpenTerminal = onOpenTerminal,
            onOpenProject = onOpenProject,
            onNotice = { notice = it },
            onDismiss = { sheetFor = null },
            scope = scope,
            ioDispatcher = ioDispatcher
        )
    }
}

@Composable
private fun ProjectList(
    listing: ProjectListing,
    query: String,
    onQuery: (String) -> Unit,
    sort: ProjectSort,
    onSort: (ProjectSort) -> Unit,
    changedOnly: Boolean,
    onChangedOnly: (Boolean) -> Unit,
    nowSeconds: Long,
    onOpen: (String) -> Unit,
    onMore: (Project) -> Unit
) {
    if (listing.projects.isEmpty()) {
        Column(Modifier.padding(top = 12.dp, start = 4.dp).testTag(ProjectsTags.EMPTY)) {
            Text(stringResource(R.string.projects_empty), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.projects_empty_depth),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        return
    }
    val changedCount = listing.projects.count { it.dirty == true }
    val visible = arrangeProjects(listing.projects, sort, query, changedOnly)
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        item(key = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                ProjectSearchField(query, onQuery, stringResource(R.string.projects_search))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ProjectChip(stringResource(R.string.projects_sort_recent), sort == ProjectSort.Recent, ProjectsTags.SORT_RECENT) {
                        onSort(ProjectSort.Recent)
                    }
                    ProjectChip(stringResource(R.string.projects_sort_name), sort == ProjectSort.Name, ProjectsTags.SORT_NAME) {
                        onSort(ProjectSort.Name)
                    }
                    ProjectChip(
                        stringResource(R.string.projects_only_changed, changedCount),
                        changedOnly,
                        ProjectsTags.ONLY_CHANGED
                    ) { onChangedOnly(!changedOnly) }
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "no_match") {
                Text(
                    stringResource(R.string.projects_no_match, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp).testTag(ProjectsTags.NO_MATCH)
                )
            }
        }
        itemsIndexed(visible, key = { _, project -> project.path }) { index, project ->
            ProjectRow(
                project = project,
                position = groupPosition(index, visible.size),
                ageLabel = project.lastCommit?.let { ageLabel(commitAge(nowSeconds, it.committedAtSeconds, zone)) },
                onOpen = { onOpen(project.path) },
                onMore = { onMore(project) }
            )
        }
        if (listing.truncated) {
            item(key = "truncated") {
                Text(
                    stringResource(R.string.projects_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp).testTag(ProjectsTags.TRUNCATED)
                )
            }
        }
        item(key = "footer") {
            Text(
                stringResource(R.string.projects_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ageLabel(age: CommitAge): String = when (age) {
    is CommitAge.Minutes -> pluralStringResource(R.plurals.projects_age_minutes, age.count.toInt(), age.count)
    is CommitAge.Hours -> pluralStringResource(R.plurals.projects_age_hours, age.count.toInt(), age.count)
    CommitAge.Yesterday -> stringResource(R.string.projects_age_yesterday)
    is CommitAge.Days -> pluralStringResource(R.plurals.projects_age_days, age.count.toInt(), age.count)
    is CommitAge.Date -> stringResource(R.string.projects_age_date, age.day, age.month)
}

@Composable
private fun ProjectRow(
    project: Project,
    position: io.github.excludebarrier.orsikrelay.ui.components.GroupPosition,
    ageLabel: String?,
    onOpen: () -> Unit,
    onMore: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val status = OrsikTheme.statusColors
    val changed = project.dirty == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(position.shape())
            .background(colors.surfaceContainerLow)
            .clickable(onClickLabel = stringResource(R.string.projects_open_label), onClick = onOpen)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
            .testTag(ProjectsTags.PROJECT_PREFIX + project.path)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (changed) status.warnContainer else colors.surfaceContainer)
        ) {
            Icon(
                OrsikIcons.Code,
                contentDescription = null,
                tint = if (changed) status.warn else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    style = OrsikType.listTitle,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                when (project.dirty) {
                    true -> StateChip(stringResource(R.string.projects_changed), status.warnContainer, status.onWarnContainer, outlined = false)
                    false -> StateChip(stringResource(R.string.projects_clean), colors.surfaceContainerLow, colors.onSurfaceVariant, outlined = true)
                    null -> Unit
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                project.branch?.let { branch ->
                    Icon(OrsikIcons.Branch, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(branch, fontFamily = OrsikMono, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                }
                val commit = project.lastCommit
                val rest = if (commit != null) {
                    listOfNotNull(commit.subject.takeIf { it.isNotBlank() }, ageLabel).joinToString(" · ")
                } else {
                    stringResource(R.string.projects_no_commit)
                }
                Text(
                    text = (if (project.branch != null) " · " else "") + rest,
                    style = OrsikType.meta,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.testTag(ProjectsTags.MORE_PREFIX + project.path)) {
            Icon(
                OrsikIcons.MoreVert,
                contentDescription = stringResource(R.string.projects_actions, project.name),
                tint = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateChip(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, outlined: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(shape)
            .background(container)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

@Composable
private fun ProjectChip(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.heightIn(min = 30.dp).testTag(testTag)
    )
}

@Composable
private fun ProjectSearchField(query: String, onQuery: (String) -> Unit, hint: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }.testTag(ProjectsTags.SEARCH)
            )
        }
    }
}

/**
 * The ⋮ sheet of one project. The absolute folder is read once from the
 * existing file listing (the project list only carries relative paths);
 * until it is known, and with an older bridge that does not report it,
 * "Im Terminal öffnen" is left out, because a `cd` to a guessed path would
 * be wrong.
 */
@Composable
private fun ProjectSheet(
    profileId: String,
    source: FilesSource,
    project: Project,
    hermes: FilesHermes?,
    onOpenTerminal: ((String) -> Unit)?,
    onOpenProject: (String) -> Unit,
    onNotice: (Int) -> Unit,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher
) {
    val clipboard = LocalClipboard.current
    val root by produceState<String?>(null, profileId) {
        value = try {
            (withContext(ioDispatcher) { source.list(profileId, "") } as? FilesResult.Ready)?.value?.root
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
    val path = serverPath(root, project.path)
    val meta = listOfNotNull(path, project.branch, project.lastCommit?.short).joinToString(" · ")
    val question = project.branch?.let { stringResource(R.string.projects_ask_question_branch, it) }
        ?: stringResource(R.string.projects_ask_question)
    FileActionSheet(
        type = FileType.Folder,
        title = project.name,
        meta = meta,
        actions = listOfNotNull(
            FileAction(OrsikIcons.Folder, stringResource(R.string.projects_action_files), testTag = ProjectsTags.ACTION_FILES) {
                onDismiss()
                onOpenProject(project.path)
            },
            hermes?.let { h ->
                FileAction(OrsikIcons.Chat, stringResource(R.string.projects_action_ask), testTag = ProjectsTags.ACTION_ASK) {
                    onDismiss()
                    scope.launch {
                        when (h.ask(path, question)) {
                            FilesAskResult.Opened -> Unit
                            FilesAskResult.Busy -> onNotice(R.string.files_ask_busy)
                            FilesAskResult.Failed -> onNotice(R.string.files_ask_failed)
                        }
                    }
                }
            },
            if (onOpenTerminal != null && root != null) {
                FileAction(OrsikIcons.Terminal, stringResource(R.string.projects_action_terminal), testTag = ProjectsTags.ACTION_TERMINAL) {
                    onDismiss()
                    onOpenTerminal(projectTerminalCommand(path))
                }
            } else {
                null
            },
            FileAction(OrsikIcons.Link, stringResource(R.string.files_copy_path), testTag = ProjectsTags.ACTION_COPY) {
                onDismiss()
                scope.launch { clipboard.setClipEntry(ClipEntry(sensitivePlainText(path))) }
                onNotice(R.string.files_path_copied)
            }
        ),
        onDismiss = onDismiss
    )
}
