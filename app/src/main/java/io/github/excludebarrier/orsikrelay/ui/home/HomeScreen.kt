package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.components.AgentComposerDock
import io.github.excludebarrier.orsikrelay.ui.components.BottomDockArea
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
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
 * The Home start page: server header (collapsing into a compact bar while
 * scrolling), suggestions, running tasks, server status, quick access,
 * recent sessions and the fixed composer dock. Stateless; [HomeRoute]
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
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit = {},
    onQuickTask: (QuickTask) -> Unit = {},
    onOpenServer: () -> Unit = {}
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val serverName = state.serverName ?: stringResource(R.string.home_no_server_name)
    val connected = state.connection == HomeConnection.Connected
    val listState = rememberLazyListState()
    // The large header is item 0; once it is gone the compact bar takes over.
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .testTag(HomeTags.SCREEN)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier
                    .fillMaxSize()
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
                if (connected) {
                    item(key = "suggestions") {
                        SuggestionChips(
                            enabled = state.composerEnabled,
                            onSuggestion = onDraftChange,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .testTag(HomeTags.SUGGESTIONS)
                        )
                    }
                }
                when (val connection = state.connection) {
                    HomeConnection.Checking -> item(key = "skeleton") { HomeSkeleton(Modifier.gutter()) }
                    is HomeConnection.Unreachable -> {
                        item(key = "offline") {
                            OfflineCard(
                                serverName = serverName,
                                reason = connection.reason,
                                onRetry = onRetry,
                                onSwitchServer = onSwitchServer,
                                modifier = Modifier.gutter()
                            )
                        }
                        runsSection(state.runs, onOpenRun)
                    }
                    HomeConnection.Connected -> {
                        runsSection(state.runs, onOpenRun)
                        item(key = "server") { ServerStatusStrip(metrics = state.hostMetrics, modifier = Modifier.gutter()) }
                        item(key = "quick_title") {
                            SectionTitle(stringResource(R.string.home_section_quick), Modifier.gutter())
                        }
                        item(key = "quick") { QuickActionGrid(onNewChat = onNewChat, onOpenServer = onOpenServer, onQuickTask = onQuickTask, modifier = Modifier.gutter()) }
                        item(key = "recent_title") {
                            SectionTitle(stringResource(R.string.home_section_recent), Modifier.gutter()) {
                                TextButton(
                                    onClick = onOpenAllSessions,
                                    modifier = Modifier.testTag(HomeTags.ALL_SESSIONS)
                                ) {
                                    Text(
                                        stringResource(R.string.home_all_sessions),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
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
                                itemsIndexed(recent.sessions, key = { _, it -> "recent_" + it.id }) { index, session ->
                                    RecentSessionRow(
                                        session = session,
                                        position = groupPosition(index, recent.sessions.size),
                                        onClick = { onOpenSession(session) },
                                        modifier = Modifier
                                            .gutter()
                                            .padding(bottom = GroupGap)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Fully qualified: inside the Column the ColumnScope overload would win.
            androidx.compose.animation.AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                CompactServerHeader(
                    serverName = serverName,
                    connection = state.connection,
                    onSwitchServer = onSwitchServer,
                    onOpenSettings = onOpenSettings
                )
            }
        }
        BottomDockArea(modifier = Modifier.testTag(HomeTags.DOCK)) {
            state.notice?.let { notice ->
                Text(
                    text = stringResource(notice.textRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(HomeTags.NOTICE)
                )
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
                onSend = onSend,
                onOpenAgentPicker = { pickerOpen = true },
                attachEnabled = state.composerEnabled && state.attachmentName == null,
                attachBusy = state.attachmentBusy,
                onAttach = onAttach,
                above = state.attachmentName?.let { name ->
                    {
                        InputChip(
                            selected = false,
                            onClick = onRemoveAttachment,
                            label = { Text(name, maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.home_composer_remove_attachment, name),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            )
        }
    }
    if (pickerOpen) {
        HarnessPickerSheet(serverName = serverName, connected = connected, onDismiss = { pickerOpen = false })
    }
}

/** The 16 dp side gutter of the Home cards; the chip row bleeds to the edge. */
private fun Modifier.gutter(): Modifier = padding(horizontal = 16.dp)

private fun androidx.compose.foundation.lazy.LazyListScope.runsSection(
    runs: List<HomeRunItem>,
    onOpenRun: (String) -> Unit
) {
    if (runs.isEmpty()) return
    item(key = "running_title") {
        SectionTitle(stringResource(R.string.home_section_running), Modifier.gutter(), countBadge = runs.size)
    }
    items(runs, key = { "run_" + it.sessionId }) { run ->
        if (run.awaitingApproval) {
            ApprovalBanner(
                title = run.title,
                onReview = { onOpenRun(run.sessionId) },
                modifier = Modifier.gutter().padding(bottom = 8.dp)
            )
        } else {
            ActiveRunRow(
                item = run,
                onClick = { onOpenRun(run.sessionId) },
                modifier = Modifier.gutter().padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

private fun HomeNotice.textRes(): Int = when (this) {
    HomeNotice.SendFailed -> R.string.home_send_failed
    HomeNotice.Busy -> R.string.home_send_busy
    HomeNotice.AttachmentUnsupported -> R.string.chat_attachment_error_type
    HomeNotice.AttachmentTooLarge -> R.string.chat_attachment_error_size
    HomeNotice.AttachmentUnreadable -> R.string.chat_attachment_error_unreadable
    HomeNotice.NewChatFailed -> R.string.new_session_error_generic
    HomeNotice.CommandInChat -> R.string.home_command_in_chat
}
