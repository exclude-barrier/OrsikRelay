package io.github.excludebarrier.orsikrelay.ui.files

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.GroupedListItem
import io.github.excludebarrier.orsikrelay.ui.components.ListIconBox
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLoadingIndicator
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ProjectsTags {
    const val SCREEN = "projects_screen"
    const val PROJECT_PREFIX = "projects_item_"
}

/**
 * The git projects in the server folder: branch, changed or clean, last
 * commit. Tapping one opens it in the file browser.
 */
@Composable
internal fun ProjectsRoute(
    profileId: String,
    source: FilesSource,
    onOpenProject: (path: String) -> Unit,
    onBack: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var reload by remember { mutableIntStateOf(0) }
    val listing by produceState<FilesResult<ProjectListing>?>(null, profileId, reload) {
        value = null
        value = withContext(ioDispatcher) { source.projects(profileId) }
    }
    Column(Modifier.fillMaxSize().testTag(ProjectsTags.SCREEN)) {
        OrsikLargeHeader(title = stringResource(R.string.projects_title), onBack = onBack, backTestTag = "projects_back")
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val result = listing) {
                null -> OrsikLoadingIndicator(Modifier.align(Alignment.Center))
                is FilesResult.Ready -> ProjectList(result.value, onOpenProject)
                else -> FilesProblem(result, onRetry = { reload++ })
            }
        }
    }
}

@Composable
private fun ProjectList(listing: ProjectListing, onOpen: (String) -> Unit) {
    if (listing.projects.isEmpty()) {
        Text(stringResource(R.string.projects_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        itemsIndexed(listing.projects, key = { _, project -> project.path }) { index, project ->
            GroupedListItem(
                position = groupPosition(index, listing.projects.size),
                title = project.name,
                supporting = describe(project),
                onClick = { onOpen(project.path) },
                onClickLabel = stringResource(R.string.projects_open_label),
                leading = { ListIconBox(OrsikIcons.Code) },
                modifier = Modifier.testTag(ProjectsTags.PROJECT_PREFIX + project.path)
            )
        }
        if (listing.truncated) {
            item {
                Text(stringResource(R.string.projects_truncated), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun describe(project: Project): String {
    val state = when (project.dirty) {
        true -> stringResource(R.string.projects_changed)
        false -> stringResource(R.string.projects_clean)
        null -> null
    }
    val commit = project.lastCommit?.let { commit ->
        val age = DateUtils.getRelativeTimeSpanString(
            commit.committedAtSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        "${commit.subject} · $age"
    }
    val head = listOfNotNull(project.branch, state).joinToString(" · ")
    return listOfNotNull(head.ifEmpty { null }, commit).joinToString("\n")
}
