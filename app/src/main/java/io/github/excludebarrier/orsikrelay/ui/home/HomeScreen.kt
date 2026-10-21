package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession

/**
 * The Home start page: server header, running tasks, server status, quick
 * access, recent sessions and the composer dock. Stateless; [HomeRoute]
 * wires the data sources. Approvals are only shown here - deciding them
 * stays in the chat's approval dialog.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onOpenRun: (sessionId: String) -> Unit,
    onOpenSession: (HermesSession) -> Unit,
    onOpenAllSessions: () -> Unit,
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val serverName = state.serverName ?: stringResource(R.string.home_no_server_name)
    val connected = state.connection == HomeConnection.Connected
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .testTag(HomeTags.SCREEN)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(HomeTags.LIST)
        ) {
            item(key = "header") {
                ServerHeader(
                    serverName = serverName,
                    connection = state.connection,
                    onSwitchServer = onSwitchServer,
                    onOpenSettings = onOpenSettings
                )
            }
            when (val connection = state.connection) {
                HomeConnection.Checking -> item(key = "skeleton") { HomeSkeleton() }
                is HomeConnection.Unreachable -> {
                    item(key = "offline") {
                        OfflineCard(
                            serverName = serverName,
                            reason = connection.reason,
                            onRetry = onRetry,
                            onSwitchServer = onSwitchServer
                        )
                    }
                    runsSection(state.runs, onOpenRun)
                }
                HomeConnection.Connected -> {
                    runsSection(state.runs, onOpenRun)
                    item(key = "server") { ServerStatusStrip(connected = true) }
                    item(key = "quick_title") { SectionTitle(stringResource(R.string.home_section_quick)) }
                    item(key = "quick") { QuickActionGrid(onOpenChat = onOpenAllSessions) }
                    item(key = "recent_title") {
                        SectionTitle(stringResource(R.string.home_section_recent)) {
                            TextButton(
                                onClick = onOpenAllSessions,
                                modifier = Modifier.testTag(HomeTags.ALL_SESSIONS)
                            ) { Text(stringResource(R.string.home_all_sessions)) }
                        }
                    }
                    when (val recent = state.recent) {
                        HomeRecent.Loading -> Unit
                        HomeRecent.Failed -> item(key = "recent_failed") {
                            HintText(stringResource(R.string.home_recent_failed))
                        }
                        is HomeRecent.Ready -> if (recent.sessions.isEmpty()) {
                            item(key = "recent_empty") { HintText(stringResource(R.string.home_recent_empty)) }
                        } else {
                            items(recent.sessions, key = { "recent_" + it.id }) { session ->
                                RecentSessionRow(session = session, onClick = { onOpenSession(session) })
                            }
                        }
                    }
                }
            }
        }
        state.notice?.let { notice ->
            Text(
                text = stringResource(notice.textRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(HomeTags.NOTICE)
            )
        }
        if (connected) {
            SuggestionChips(enabled = state.composerEnabled, onSuggestion = onDraftChange)
        }
        AgentComposerDock(
            draft = state.draft,
            onDraftChange = onDraftChange,
            hint = stringResource(
                when (state.connection) {
                    HomeConnection.Connected -> R.string.home_composer_hint
                    HomeConnection.Checking -> R.string.home_composer_hint_checking
                    is HomeConnection.Unreachable -> R.string.home_composer_hint_offline
                }
            ),
            enabled = state.composerEnabled,
            canSend = state.canSend,
            sending = state.sending,
            attachmentName = state.attachmentName,
            attachmentBusy = state.attachmentBusy,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            onOpenAgentPicker = { pickerOpen = true },
            onSend = onSend
        )
    }
    if (pickerOpen) {
        HarnessPickerSheet(serverName = serverName, connected = connected, onDismiss = { pickerOpen = false })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.runsSection(
    runs: List<HomeRunItem>,
    onOpenRun: (String) -> Unit
) {
    if (runs.isEmpty()) return
    item(key = "running_title") {
        SectionTitle(stringResource(R.string.home_section_running)) {
            Text(
                text = runs.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
    items(runs, key = { "run_" + it.sessionId }) { run ->
        if (run.awaitingApproval) {
            ApprovalBanner(title = run.title, onReview = { onOpenRun(run.sessionId) })
        } else {
            ActiveRunRow(item = run, onClick = { onOpenRun(run.sessionId) })
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

private fun HomeNotice.textRes(): Int = when (this) {
    HomeNotice.SendFailed -> R.string.home_send_failed
    HomeNotice.Busy -> R.string.home_send_busy
    HomeNotice.AttachmentUnsupported -> R.string.chat_attachment_error_type
    HomeNotice.AttachmentTooLarge -> R.string.chat_attachment_error_size
    HomeNotice.AttachmentUnreadable -> R.string.chat_attachment_error_unreadable
}
