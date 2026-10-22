package io.github.excludebarrier.orsikrelay.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.ui.components.EmptyState
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.home.ActiveRunRow
import io.github.excludebarrier.orsikrelay.ui.home.ApprovalBanner
import io.github.excludebarrier.orsikrelay.ui.home.HomeRunItem
import io.github.excludebarrier.orsikrelay.ui.home.SectionTitle
import io.github.excludebarrier.orsikrelay.ui.home.rememberRunItems

/** Test tags of the Activity tab. */
object ActivityTags {
    const val SCREEN = "activity_screen"
    const val EMPTY = "activity_empty"
}

/**
 * The Activity tab: what Hermes is doing for this app right now - open
 * approvals first, then running tasks. Both come from the same run
 * tracking as Home. Tapping an item opens its chat; an approval is only
 * decided there, in the approval dialog.
 */
@Composable
fun ActivityRoute(
    profileId: String,
    runController: RunSessionController,
    runStatusRequester: RunStatusRequester,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val runs = rememberRunItems(profileId, runController, runStatusRequester)
    ActivityScreen(runs = runs, onOpenRun = { run -> onOpenChat(run.sessionId, run.title) }, modifier = modifier)
}

@Composable
fun ActivityScreen(
    runs: List<HomeRunItem>,
    onOpenRun: (HomeRunItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(ActivityTags.SCREEN)
    ) {
        OrsikLargeHeader(title = stringResource(R.string.nav_activity))
        if (runs.isEmpty()) {
            EmptyState(
                icon = OrsikIcons.Activity,
                title = stringResource(R.string.activity_empty_title),
                hint = stringResource(R.string.activity_empty_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp)
                    .testTag(ActivityTags.EMPTY)
            )
            return@Column
        }
        val approvals = runs.filter { it.awaitingApproval }
        val running = runs.filterNot { it.awaitingApproval }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (approvals.isNotEmpty()) {
                item(key = "approvals_title") {
                    SectionTitle(stringResource(R.string.activity_section_approvals), countBadge = approvals.size)
                }
                items(approvals, key = { "approval_" + it.sessionId }) { run ->
                    ApprovalBanner(
                        title = run.title,
                        onReview = { onOpenRun(run) },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            if (running.isNotEmpty()) {
                item(key = "running_title") {
                    SectionTitle(stringResource(R.string.home_section_running), countBadge = running.size)
                }
                items(running, key = { "run_" + it.sessionId }) { run ->
                    ActiveRunRow(item = run, onClick = { onOpenRun(run) }, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}
