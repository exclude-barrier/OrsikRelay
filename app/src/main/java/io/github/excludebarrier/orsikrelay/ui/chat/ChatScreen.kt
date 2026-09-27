package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.notification.NotificationPermissionStore
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationPermissionRequester
import io.github.excludebarrier.orsikrelay.ui.theme.brandSurface
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage

/**
 * Chat screen with the live turn slice (Mission 006A) — thin presentation
 * layer (P1 refactor).
 *
 * This composable holds no business state. It renders [ChatUiState] published
 * by a [ChatViewModel] and forwards every user intent (draft, send, stop,
 * steer, recoverable-steer move, refresh, back, approval dismissal) to the
 * view model. The entire turn state machine — history load, draft
 * persistence, streaming, run stop, run steer, recoverable steers, back and
 * approval — lives in [ChatViewModel], which is keyed by `(profileId,
 * sessionId)`: a key switch or leaving composition recreates it, cancelling
 * the previous session's collectors and flushing the pending draft (Mission
 * 007B/008B/009A).
 *
 * The public signature is unchanged: the same seams are injected and the same
 * test tags are rendered, so the synthetic Compose tests keep exercising the
 * exact UI contract. Network-protocol logic stays in the repository/pipeline
 * layer below [StreamingChatSource].
 */
@Composable
fun ChatScreen(
    historySource: MessageHistorySource,
    runController: RunSessionController,
    runStopRequester: RunStopRequester,
    runSteerRequester: RunSteerRequester,
    chatDraftRepository: ChatDraftRepository,
    runApprovalRequester: RunApprovalRequester = NoOpRunApprovalRequester,
    notificationPermissionStore: NotificationPermissionStore = NoOpNotificationPermissionStore,
    profileId: String,
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The tool-activity labels are resolved here (localized UI strings) and
    // handed to the view model, which needs them to build the generic local
    // tool rows — never a tool name or payload.
    val toolActivityLabel = stringResource(R.string.chat_tool_activity)
    val namedToolActivityLabel = stringResource(R.string.chat_tool_activity_named)
    val stopLabel = stringResource(R.string.chat_stop)
    val stoppingLabel = stringResource(R.string.chat_stopping)
    val stopErrorLabel = stringResource(R.string.error_run_stop_generic)
    val steerLabel = stringResource(R.string.chat_steer)
    val steerInputLabel = stringResource(R.string.chat_steer_input_label)
    val steerQueuedLabel = stringResource(R.string.chat_steer_queued)
    val steerErrorLabel = stringResource(R.string.error_run_steer_generic)
    val steerRecoveryLabel = stringResource(R.string.chat_steer_recovery_note)
    val steerSendAsNewMessageLabel = stringResource(R.string.chat_steer_send_as_new_message)

    val viewModel = remember(profileId, sessionId) {
        ChatViewModel(
            historySource = historySource,
            runController = runController,
            runStopRequester = runStopRequester,
            runSteerRequester = runSteerRequester,
            chatDraftRepository = chatDraftRepository,
            profileId = profileId,
            sessionId = sessionId,
            onBack = onBack,
            toolActivityLabel = toolActivityLabel,
            namedToolActivityLabel = namedToolActivityLabel
        )
    }

    // A key switch (or leaving composition) cancels the running collector AND
    // any in-flight stop/steer request of the previous session and
    // deterministically flushes the pending ordinary draft (Mission
    // 007B/009A). No state is published after cancellation.
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    // Initial history load and draft restore for the active key, re-run on a
    // key switch (the view model is recreated, so the effect re-keys too).
    LaunchedEffect(viewModel) {
        viewModel.loadHistory()
        viewModel.restoreDraft()
    }

    val state by viewModel.state.collectAsState()
    val timelineListState = remember(profileId, sessionId) { LazyListState() }

    // System and gesture back (Mission 006A): the platform back event goes
    // through exactly the same path as the visible button, so a running stream
    // is always canceled before the navigation back to the session list.
BackHandler(enabled = true, onBack = { viewModel.handleBack() })

    // P3: contextual POST_NOTIFICATIONS request (Android 13+), triggered when
    // a run is active in the foreground and the permission has not been
    // requested yet. A denial is respected and never re-asked; the app stays
    // fully functional without notifications.
    NotificationPermissionRequester(
        store = notificationPermissionStore,
        isRunActive = state.turnActive
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            // Edge-to-edge IME handling (official Compose e2e pattern): the
            // window is never panned for the keyboard. The IME inset is
            // consumed exactly once, here at the chat root, so the Column
            // resizes from the bottom: the header stays put, the message
            // list (weight 1f) shrinks, and the composer sits directly
            // above the keyboard. `exclude(navigationBars)` subtracts the
            // navigation-bar portion, which the root HermesAppSurface
            // already applies via safeDrawingPadding() — without it the
            // nav bar would be padded twice while the IME is open.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
onClick = { viewModel.handleBack() }
            ) {
                Text(stringResource(R.string.chat_back))
            }
            Text(
                text = stringResource(R.string.chat_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        when (val current = state.history) {
            MessageHistoryState.Loading -> StatusText(stringResource(R.string.chat_loading))

            is MessageHistoryState.Failed -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (current.error) {
                        MessageHistoryError.MissingProfile ->
                            stringResource(R.string.error_session_profile)
                        MessageHistoryError.SessionNotFound ->
                            stringResource(R.string.error_chat_not_found)
                        else -> stringResource(R.string.error_chat_generic)
                    },
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { viewModel.loadHistory() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.sessions_retry))
                }
            }

            is MessageHistoryState.Ready -> {
                val timeline = buildTimeline(current.messages, state.localLines)
                LaunchedEffect(timeline.size) {
                    if (timeline.isNotEmpty()) {
                        timelineListState.scrollToItem(timeline.lastIndex)
                    }
                }
                if (timeline.isEmpty()) {
                    StatusText(stringResource(R.string.chat_empty))
                } else {
                    LazyColumn(
                        state = timelineListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(timeline, key = { it.key }) { message ->
                            MessageRow(message)
                        }
                    }
                }
                val failure = state.turnFailure
                if (failure != null) {
                    TurnFailureBlock(
                        error = failure,
                        showRefreshHistory = state.runStarted,
                        onRefreshHistory = { viewModel.refreshHistory() }
                    )
                }
                // Stop control (Mission 007B) and steer control (Mission 008B):
                // visible only for an active turn whose server-confirmed,
                // non-blank run ID is bound.
                if (state.controlsVisible) {
                    StopControl(
                        label = if (state.stopInFlight) stoppingLabel else stopLabel,
                        enabled = !state.stopInFlight,
                        showStopError = state.stopFailed && !state.stopInFlight,
                        stopErrorLabel = stopErrorLabel,
                        onStop = { viewModel.stop() },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    SteerControl(
                        draft = state.steerDraft,
                        onDraftChange = { viewModel.onSteerDraftChange(it) },
                        inFlight = state.steerInFlight,
                        queued = state.steerQueued,
                        failed = state.steerFailed,
                        steerLabel = steerLabel,
                        inputLabel = steerInputLabel,
                        queuedLabel = steerQueuedLabel,
                        errorLabel = steerErrorLabel,
                        onSteer = { viewModel.steer() },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Recoverable steers (Mission 008B): each preserved item is
                // rendered as its own ordered block.
                if (state.steerRecovery.isNotEmpty()) {
                    state.steerRecovery.forEachIndexed { index, text ->
                        SteerRecoveryBlock(
                            note = "$steerRecoveryLabel ${index + 1}/${state.steerRecovery.size}",
                            text = text,
                            actionLabel = steerSendAsNewMessageLabel,
                            enabled = state.draft.isBlank(),
                            onSendAsNewMessage = { viewModel.sendRecoveryAsNewMessage(index) },
                            testTag = "chatSteerRecovery:$index",
                            actionTestTag = "chatSteerSendAsNewMessage:$index",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // P4: transient re-attach status while the stream is
                // being re-attached to the still-running server run.
                if (state.reconnecting) {
                    StatusText(stringResource(R.string.chat_stream_reconnecting))
                }
                ComposerBar(
                    draft = state.draft,
                    onDraftChange = { viewModel.onDraftChange(it) },
                    sendEnabled = state.sendEnabled,
                    onSend = { viewModel.send() },
                    turnActive = state.turnActive
                )
            }
        }
    }
    // A strictly validated protocol-v1 `approval.request` is emitted by the
    // authenticated chat stream only after the complete capability contract
    // was negotiated. The event itself is stronger evidence than potentially
    // stale UI metadata, so ApprovalUi renders a non-null validated request.
    ApprovalUi(
        pendingApproval = state.pendingApproval,
        profileId = profileId,
        requester = runApprovalRequester,
        onDismiss = { viewModel.dismissApproval() },
        onFinished = { viewModel.finishApproval() }
    )
}

@Composable
private fun TurnFailureBlock(
    error: ChatStreamError,
    showRefreshHistory: Boolean,
    onRefreshHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (error) {
                ChatStreamError.InvalidCredentials ->
                    stringResource(R.string.error_connection_invalid_credentials)
                ChatStreamError.SessionNotFound ->
                    stringResource(R.string.error_chat_not_found)
                ChatStreamError.RateLimited ->
                    stringResource(R.string.error_connection_rate_limited)
                ChatStreamError.Tls ->
                    stringResource(R.string.error_connection_tls)
                ChatStreamError.Timeout ->
                    stringResource(R.string.error_connection_timeout)
                ChatStreamError.Network ->
                    stringResource(R.string.error_connection_network)
                ChatStreamError.IncompatibleApi ->
                    stringResource(R.string.error_connection_incompatible_api)
                ChatStreamError.Http,
                ChatStreamError.InvalidInput,
                ChatStreamError.Conflict,
                ChatStreamError.ResumeUnavailable,
                ChatStreamError.ResumeGap ->
                    stringResource(R.string.error_connection_http)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (showRefreshHistory) {
            Button(
                onClick = onRefreshHistory,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.chat_refresh_history))
            }
        }
    }
}

/**
 * The run-stop control (Mission 007B). [label] is the "Stop" label or, while a
 * stop request is in flight, the "stopping" label; [enabled] is false while
 * the request is in flight. [showStopError] renders the one closed, generic
 * stop failure message (no dynamic server detail, no run ID).
 */
@Composable
private fun StopControl(
    label: String,
    enabled: Boolean,
    showStopError: Boolean,
    stopErrorLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showStopError) {
            Text(
                text = stopErrorLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        OutlinedButton(
            onClick = onStop,
            enabled = enabled
        ) {
            Text(label)
        }
    }
}

/**
 * The run-steer control (Mission 008B). A distinct input plus its own "steer"
 * action, only rendered for an active turn with a bound, non-blank run ID.
 * [inFlight] disables input and action; [queued] renders the acknowledgement;
 * [failed] renders the one closed, generic failure message and keeps the text
 * in [draft] for a deliberate manual retry.
 */
@Composable
private fun SteerControl(
    draft: String,
    onDraftChange: (String) -> Unit,
    inFlight: Boolean,
    queued: Boolean,
    failed: Boolean,
    steerLabel: String,
    inputLabel: String,
    queuedLabel: String,
    errorLabel: String,
    onSteer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text(inputLabel) },
                enabled = !inFlight,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .testTag("chatSteerInput")
            )
            OutlinedButton(
                onClick = onSteer,
                enabled = !inFlight,
                modifier = Modifier.testTag("chatSteerSend")
            ) {
                Text(steerLabel)
            }
        }
        if (inFlight) {
            Text(
                text = stringResource(R.string.chat_steering),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("chatSteeringStatus")
            )
        } else if (queued) {
            Text(
                text = queuedLabel,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("chatSteerQueuedStatus")
            )
        } else if (failed) {
            Text(
                text = errorLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("chatSteerFailedStatus")
            )
        }
    }
}

/**
 * One ordered block for a single recoverable steer (Mission 008B). [note] is
 * the generic recoverable note with an `index/total` counter; [text] is the
 * exact preserved item text. The single [actionLabel] action moves exactly
 * THIS one item into the ordinary composer; it never sends it and is disabled
 * — and a no-op in the view model — while [enabled] is false (i.e. the
 * ordinary composer is nonblank), so it can never overwrite the user's
 * in-progress message.
 */
@Composable
private fun SteerRecoveryBlock(
    note: String,
    text: String,
    actionLabel: String,
    enabled: Boolean,
    onSendAsNewMessage: () -> Unit,
    testTag: String,
    actionTestTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("chatSteerRecoveryText")
        )
        Button(
            onClick = onSendAsNewMessage,
            enabled = enabled,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag(actionTestTag)
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    turnActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(stringResource(R.string.chat_input_label)) },
            enabled = !turnActive,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Button(
            onClick = onSend,
            enabled = sendEnabled
        ) {
            Text(stringResource(R.string.chat_send))
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    )
}

@Composable
private fun MessageRow(message: TimelineRow) {
    val role = when (message.role) {
        MessageRole.USER -> stringResource(R.string.chat_role_user)
        MessageRole.ASSISTANT -> stringResource(R.string.chat_role_assistant)
        MessageRole.SYSTEM -> stringResource(R.string.chat_role_system)
        MessageRole.TOOL -> if (message.localTool) {
            // Ephemeral live tool activity: generic status only, never a tool
            // name (those exist only for server history lines).
            stringResource(R.string.chat_role_tool)
        } else {
            message.toolName?.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.chat_role_tool_named, it)
            } ?: stringResource(R.string.chat_tool_activity_history)
        }
    }
    // User messages get the brand-tinted bubble; assistant/system/tool use a
    // neutral outlined card. Both keep the role label and the message text.
    val isUser = message.role == MessageRole.USER
    val bubbleBackground = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("chatTimelineRow:${message.key}")
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
        )
        // SECURITY (history tool redaction): a history TOOL row renders ONLY
        // its safe role label above — never a content bubble. The timeline
        // already strips the untrusted `content` (see buildTimeline); this
        // keeps the UI fail closed even if a future path ever carried
        // verbatim tool data (commands, paths, output, payloads). Live
        // (localTool) rows keep their existing app-generated status bubble.
        if (message.role != MessageRole.TOOL || message.localTool) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .brandSurface(background = bubbleBackground, contentPadding = 14.dp)
            ) {
                if (message.role == MessageRole.ASSISTANT) {
                    // Assistant output renders as markdown (P2); user, system
                    // and tool lines stay plain text so markdown can never
                    // surface user input or tool/approval detail.
                    AssistantMarkdownMessage(content = message.content)
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/**
 * A no-op notification-permission store used as the default for the chat
 * screen (and by the existing instrumented tests): it reports the permission
 * as already requested, so the contextual request is never triggered and the
 * screen behavior is unchanged when no real store is supplied.
 */
internal object NoOpNotificationPermissionStore : NotificationPermissionStore {
    override val hasRequested: Boolean
        get() = true

    override fun markRequested() = Unit
}
