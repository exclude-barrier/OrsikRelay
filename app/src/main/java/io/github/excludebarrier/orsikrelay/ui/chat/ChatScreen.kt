package io.github.excludebarrier.orsikrelay.ui.chat

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.components.rememberReducedMotion
import io.github.excludebarrier.orsikrelay.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.components.AgentComposerDock
import io.github.excludebarrier.orsikrelay.ui.components.AgentMark
import io.github.excludebarrier.orsikrelay.ui.components.BottomDockArea
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.notification.NotificationPermissionStore
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationPermissionRequester
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.settings.ChatTextSizeController
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import io.github.excludebarrier.orsikrelay.platform.attachment.ChatAttachmentThumbnails
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

/**
 * Chat screen with the live turn slice — thin presentation
 * layer.
 *
 * This composable holds no business state. It renders [ChatUiState] published
 * by a [ChatViewModel] and forwards every user intent (draft, send, stop,
 * steer, recoverable-steer move, refresh, back, approval dismissal) to the
 * view model. The entire turn state machine — history load, draft
 * persistence, streaming, run stop, run steer, recoverable steers, back and
 * approval — lives in [ChatViewModel], which is keyed by `(profileId,
 * sessionId)`: a key switch or leaving composition recreates it, cancelling
 * the previous session's collectors and flushing the pending draft.
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
    runStatusRequester: RunStatusRequester = NoOpRunStatusRequester,
    attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
    notificationPermissionStore: NotificationPermissionStore = NoOpNotificationPermissionStore,
    chatTextSizeController: ChatTextSizeController? = null,
    profileId: String,
    sessionId: String,
    // Session title and server name are presentation-only metadata. The
    // navigation host forwards the title it already received from the
    // session list; when neither is available (notification tap, process
    // recreation) the header falls back to the generic chat title.
    sessionTitle: String? = null,
    serverName: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chatTextScale = (chatTextSizeController?.size ?: ChatTextSize.DEFAULT).scale
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
    val steerClearDraftHintLabel = stringResource(R.string.chat_steer_recovery_clear_draft_hint)
    val steerSendAsNewMessageLabel = stringResource(R.string.chat_steer_send_as_new_message)
    val attachmentAddLabel = stringResource(R.string.chat_attachment_add)
    val attachmentRemoveLabel = stringResource(R.string.chat_attachment_remove)
    val attachmentBusyLabel = stringResource(R.string.chat_attachment_busy)
    val attachmentTypeErrorLabel = stringResource(R.string.chat_attachment_error_type)
    val attachmentSizeErrorLabel = stringResource(R.string.chat_attachment_error_size)
    val attachmentUnreadableErrorLabel = stringResource(R.string.chat_attachment_error_unreadable)

    val viewModel = remember(profileId, sessionId) {
        ChatViewModel(
            historySource = historySource,
            runController = runController,
            runStopRequester = runStopRequester,
            runSteerRequester = runSteerRequester,
            runStatusRequester = runStatusRequester,
            chatDraftRepository = chatDraftRepository,
            attachmentSource = attachmentSource,
            profileId = profileId,
            sessionId = sessionId,
            onBack = onBack,
            toolActivityLabel = toolActivityLabel,
            namedToolActivityLabel = namedToolActivityLabel
        )
    }

    // A key switch (or leaving composition) cancels the running collector AND
    // any in-flight stop/steer request of the previous session and
    // deterministically flushes the pending ordinary draft.
    // No state is published after cancellation.
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
    // Start of the active turn on the monotonic clock, for the elapsed time
    // of the activity row. Survives a configuration change; a re-opened
    // screen counts from when it attached to the running turn.
    val turnStartedAt = rememberSaveable(state.turnActive) { SystemClock.elapsedRealtime() }

    val context = LocalContext.current
    val pickImage = rememberImagePicker(viewModel::onAttachmentPicked)
    // The chip preview is decoded from the picked URI, bounded and local-only;
    // it is recomputed when the picked attachment changes.
    val attachmentThumbnail by produceState<ImageBitmap?>(null, state.attachment?.contentUri) {
        val uri = state.attachment?.contentUri
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ChatAttachmentThumbnails.decode(context, uri)?.asImageBitmap()
            }
        }
    }
    val timelineListState = remember(profileId, sessionId) { LazyListState() }

    // System and gesture back: the platform back event goes
    // through exactly the same path as the visible button, so a running stream
    // is always canceled before the navigation back to the session list.
BackHandler(enabled = true, onBack = { viewModel.handleBack() })

    // Contextual POST_NOTIFICATIONS request (Android 13+), triggered when
    // a run is active in the foreground and the permission has not been
    // requested yet. A denial is respected and never re-asked; the app stays
    // fully functional without notifications.
    NotificationPermissionRequester(
        store = notificationPermissionStore,
        isRunActive = state.turnActive
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Adaptive composer (landscape + keyboard): with the soft keyboard open
        // on a phone in landscape the chat root can shrink to roughly the
        // height of the header plus the composer. Measured on a 1080x2400
        // display with the Gboard landscape IME (686 px of 1080 px): ~394 px
        // (about 150 dp) are left. Header, attachment chip and input row then
        // no longer fit, and the input row collapsed to zero height, so the
        // composer has to switch to its compact spacing in that regime. The
        // decision uses the AVAILABLE height (never the orientation), so it
        // also covers split screen, freeform windows and a taller keyboard.
        val compactComposer = maxHeight < COMPACT_COMPOSER_MAX_HEIGHT
        Column(
            modifier = Modifier
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
        ChatHeader(
            title = sessionTitle?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.chat_title),
            subtitle = serverName?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.chat_header_subtitle, it) },
            onBack = { viewModel.handleBack() },
            compact = compactComposer,
            modifier = Modifier.bleed(16.dp)
        )

        when (val current = state.history) {
            MessageHistoryState.Loading -> StatusText(stringResource(R.string.chat_loading))

            is MessageHistoryState.Failed -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                val hint = when (current.error) {
                    MessageHistoryError.MissingProfile ->
                        stringResource(R.string.sessions_error_hint_missing_profile)
                    MessageHistoryError.SessionNotFound ->
                        stringResource(R.string.chat_history_error_session_not_found)
                    MessageHistoryError.Network,
                    MessageHistoryError.Timeout ->
                        stringResource(R.string.sessions_error_hint_unreachable)
                    MessageHistoryError.Tls ->
                        stringResource(R.string.sessions_error_hint_tls)
                    MessageHistoryError.InvalidCredentials ->
                        stringResource(R.string.sessions_error_hint_credentials)
                    MessageHistoryError.RateLimited ->
                        stringResource(R.string.sessions_error_hint_rate_limited)
                    MessageHistoryError.IncompatibleApi ->
                        stringResource(R.string.sessions_error_hint_incompatible_api)
                    else -> null
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { viewModel.loadHistory() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.sessions_retry))
                }
            }

            is MessageHistoryState.Ready -> {
                val timeline = buildTimeline(current.messages, state.localLines)
                // Tail-follow bookkeeping. `followTail` starts true so an
                // opened session lands at the end; only a settled USER scroll
                // re-evaluates it (true while the viewport parks within
                // FOLLOW_TAIL_THRESHOLD of the end). Content that grows while
                // the user has scrolled away must never yank the viewport.
                var followTail by remember(profileId, sessionId) { mutableStateOf(true) }
                // Which tool groups the user opened; survives recreation.
                var expandedToolGroups by rememberSaveable(profileId, sessionId) { mutableStateOf(emptySet<String>()) }
                val followThresholdPx = with(LocalDensity.current) {
                    FOLLOW_TAIL_THRESHOLD.roundToPx()
                }
                if (!compactComposer) {
                    // One collector owns both signals, so a settled user
                    // scroll and the next content growth are processed in
                    // order and can never race each other: a user scroll
                    // re-defines `followTail`; while no scroll is in progress,
                    // a layout change (a growing streamed row or a new row)
                    // scrolls to the end only when the viewport was parked at
                    // the tail. The compact regime does not compose the
                    // timeline, so no scroll state exists there.
                    LaunchedEffect(timelineListState) {
                        // Every scroll the screen did not start itself is the
                        // user's: a touch drag or fling, but also an
                        // accessibility scroll action (TalkBack, switch
                        // access), which produces no drag interaction.
                        var selfScroll = false
                        var userScrollInProgress = false
                        snapshotFlow {
                            timelineListState.isScrollInProgress to timelineListState.layoutInfo
                        }.collect { (scrolling, info) ->
                            if (scrolling) {
                                if (!selfScroll) userScrollInProgress = true
                                return@collect
                            }
                            if (userScrollInProgress) {
                                // The user's drag/fling settled: freeze the
                                // current position as the new reference.
                                userScrollInProgress = false
                                followTail = info.isAtTail(followThresholdPx)
                                return@collect
                            }
                            if (followTail && !info.isAtTail(TAIL_SCROLL_EPSILON_PX)) {
                                // `layoutInfo` changes while a frame is
                                // measuring. Forcing a remeasure from that
                                // phase throws ("performMeasureAndLayout
                                // called during measure layout"), so the
                                // scroll is deferred to the next frame.
                                withFrameNanos { }
                                selfScroll = true
                                try {
                                    timelineListState.scrollToTail()
                                } finally {
                                    selfScroll = false
                                }
                            }
                        }
                    }
                }
                // The middle region (timeline plus every status/control block)
                // is the only flexible part of the chat column: it takes
                // whatever is left after the header and the composer, and
                // shrinks - down to nothing - when the composer needs the
                // space. A compose window that is shorter than header plus
                // composer (landscape with the keyboard open, split screen)
                // must never squeeze the input row out: the composer keeps its
                // intrinsic height and this region absorbs the deficit.
                //
                // Column does NOT measure its children in declaration order:
                // it first measures every child without a weight with the
                // incoming constraints, and only then distributes the
                // remaining main-axis space to the weighted children (verified
                // against the row/column measure policy in the local
                // androidx.compose.foundation.layout artifact). The composer is
                // therefore already measured when this region's budget is
                // fixed and always keeps its intrinsic height. With `fill =
                // true` (the default) the region additionally consumes the
                // surplus space when the timeline is short or empty, which
                // pins the composer to the bottom edge instead of letting it
                // float up directly below the header.
                //
                // In the compact regime the timeline, the status lines and the
                // run controls are not composed at all: the weighted spacer
                // below keeps the whole remaining budget for the composer and
                // still places it on the bottom edge. They come back as soon as
                // there is room for them again.
                if (!compactComposer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                if (timeline.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.chat_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = timelineListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag(TAG_TIMELINE)
                    ) {
                        if (state.canLoadOlder || state.loadingOlder) {
                            item(key = "load_older") {
                                LoadOlderRow(
                                    loading = state.loadingOlder,
                                    failed = state.olderLoadError != null,
                                    onLoadOlder = { viewModel.loadOlderMessages() }
                                )
                            }
                        }
                        val grouped = groupTools(timeline)
                        items(grouped, key = { it.key }) { item ->
                            CompositionLocalProvider(
                                LocalChatTextScale provides chatTextScale
                            ) {
                                when (item) {
                                    is TimelineItem.Single -> MessageRow(item.row)
                                    is TimelineItem.ToolGroup -> ToolGroupRow(
                                        group = item,
                                        // Only the newest live group can still grow.
                                        running = state.turnActive && item.live && item === grouped.last(),
                                        expanded = item.key in expandedToolGroups,
                                        onToggle = {
                                            expandedToolGroups = if (item.key in expandedToolGroups) {
                                                expandedToolGroups - item.key
                                            } else {
                                                expandedToolGroups + item.key
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        state.turnActivity?.let { activity ->
                            item(key = "turn_activity") {
                                TurnActivityRow(
                                    activity = activity,
                                    startedAt = turnStartedAt,
                                    toolName = state.localLines.lastOrNull()?.takeIf { it.localTool }?.toolName
                                )
                            }
                        }
                    }
                }
                val failure = state.turnFailure
                if (state.turnUncertain) {
                    // Unknown run outcome. Generic, honest notice; the
                    // confirmed binding is kept and NO new message is sent.
                    // A manual status reconciliation is offered only when a
                    // server-confirmed run ID exists.
                    RunUncertainBlock(
                        inFlight = state.reconcileInFlight,
                        canReconcile = state.recoveryVisible,
                        stillActive = state.reconcileStillActive,
                        unavailable = state.reconcileFailed,
                        onReconcile = { viewModel.reconcileUncertainRun() }
                    )
                } else if (failure != null) {
                    TurnFailureBlock(
                        error = failure,
                        showRefreshHistory = state.runStarted,
                        onRefreshHistory = { viewModel.refreshHistory() }
                    )
                }
                // A rejected send (another run owns the app-wide slot).
                // Generic, closed message only - no run/session/profile ID,
                // URL, key or tool detail. The composer stays usable; the
                // note clears once the global run is terminal.
                if (state.startConflict) {
                    ConflictBlock()
                }
                // Recoverable steers: each preserved item is
                // rendered as its own ordered block. They belong above the
                // steer control: the note refers to a course correction that
                // was not taken over, so it must not sit between the steer
                // field and the message field.
                if (state.steerRecovery.isNotEmpty()) {
                    state.steerRecovery.forEachIndexed { index, text ->
                        SteerRecoveryBlock(
                            index = index,
                            total = state.steerRecovery.size,
                            text = text,
                            actionLabel = steerSendAsNewMessageLabel,
                            clearDraftHint = steerClearDraftHintLabel,
                            enabled = state.draft.isBlank(),
                            onSendAsNewMessage = { viewModel.sendRecoveryAsNewMessage(index) },
                            testTag = "chatSteerRecovery:$index",
                            actionTestTag = "chatSteerSendAsNewMessage:$index",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // Stop control and steer control:
                // visible only for an active turn whose server-confirmed,
                // non-blank run ID is bound.
                // The stop and steer actions themselves live in the composer
                // row (see [RunComposerRow]); only their closed status lines
                // stay above it.
                if (state.controlsVisible) {
                    RunStatusLines(
                        showStopError = state.stopFailed && !state.stopInFlight,
                        stopErrorLabel = stopErrorLabel,
                        steerInFlight = state.steerInFlight,
                        steerQueued = state.steerQueued,
                        steerFailed = state.steerFailed,
                        queuedLabel = steerQueuedLabel,
                        errorLabel = steerErrorLabel,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Transient re-attach status while the stream is
                // being re-attached to the still-running server run.
                if (state.reconnecting) {
                    StatusText(stringResource(R.string.chat_stream_reconnecting))
                }
                }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                ComposerBar(
                    modifier = Modifier.bleed(16.dp),
                    draft = state.draft,
                    onDraftChange = { viewModel.onDraftChange(it) },
                    sendEnabled = state.sendEnabled,
                    onSend = { viewModel.send() },
                    turnActive = state.turnActive,
                    attachment = state.attachment,
                    attachmentThumbnail = attachmentThumbnail,
                    attachmentBusy = state.attachmentBusy,
                    attachmentErrorLabel = state.attachmentError?.let { error ->
                        when (error) {
                            ChatAttachmentError.UnsupportedType ->
                                attachmentTypeErrorLabel
                            ChatAttachmentError.TooLarge -> attachmentSizeErrorLabel
                            ChatAttachmentError.Unreadable -> attachmentUnreadableErrorLabel
                        }
                    },
                    attachmentAddLabel = attachmentAddLabel,
                    attachmentRemoveLabel = attachmentRemoveLabel,
                    attachmentBusyLabel = attachmentBusyLabel,
                    onPickAttachment = pickImage,
                    onRemoveAttachment = { viewModel.removeAttachment() },
                    compact = compactComposer,
                    runControls = if (state.controlsVisible) {
                        RunComposerControls(
                            steerDraft = state.steerDraft,
                            onSteerDraftChange = { viewModel.onSteerDraftChange(it) },
                            steerInFlight = state.steerInFlight,
                            onSteer = { viewModel.steer() },
                            stopInFlight = state.stopInFlight,
                            onStop = { viewModel.stop() }
                        )
                    } else {
                        null
                    },
                    conflictNotice = state.startConflict,
                    uncertainNotice = state.turnUncertain,
                    // In the compact regime the timeline (and with it the stop,
                    // steer and recovery controls) is not composed at all. The
                    // run-critical controls move into the compact composer row:
                    // while a server-confirmed run is bound, stop and steer;
                    // while the outcome is uncertain, the single "check status"
                    // recovery action.
                    compactRunControls = when {
                        state.recoveryVisible -> {
                            {
                                OutlinedButton(
                                    onClick = { viewModel.reconcileUncertainRun() },
                                    enabled = !state.reconcileInFlight,
                                    modifier = Modifier.testTag(TAG_RUN_RECONCILE)
                                ) {
                                    Text(
                                        if (state.reconcileInFlight) {
                                            stringResource(R.string.chat_run_uncertain_checking)
                                        } else {
                                            stringResource(R.string.chat_run_uncertain_check)
                                        }
                                    )
                                }
                            }
                        }
                        state.controlsVisible -> {
                            {
                                StopButton(
                                    stopInFlight = state.stopInFlight,
                                    label = if (state.stopInFlight) stoppingLabel else stopLabel,
                                    onStop = { viewModel.stop() }
                                )
                                ChatInputField(
                                    draft = state.steerDraft,
                                    onDraftChange = { viewModel.onSteerDraftChange(it) },
                                    label = steerInputLabel,
                                    enabled = !state.steerInFlight,
                                    maxLines = COMPACT_DRAFT_MAX_LINES,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                        .testTag("chatSteerInput")
                                )
                                IconButton(
                                    onClick = { viewModel.steer() },
                                    enabled = !state.steerInFlight,
                                    modifier = Modifier.testTag("chatSteerSend")
                                ) {
                                    Icon(OrsikIcons.ArrowUp, contentDescription = steerLabel)
                                }
                            }
                        }
                        else -> null
                    }
                )
            }
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

/**
 * The chat header of design v2: back arrow, the session title (20 sp, one
 * line) and - when the caller knows it - "Hermes · <server>" below, with a
 * hairline at the bottom. The app root already applies the safe-drawing
 * insets, so the header adds no status-bar inset of its own.
 */
@Composable
internal fun ChatHeader(
    title: String,
    onBack: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(line, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }
            .padding(start = 4.dp, end = 8.dp, top = if (compact) 0.dp else 6.dp, bottom = if (compact) 0.dp else 8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = OrsikIcons.ArrowBack,
                contentDescription = stringResource(R.string.chat_back)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = title,
                style = OrsikType.detailTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_CHAT_TITLE)
            )
            if (subtitle != null && !compact) {
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Lets a full-width bar (header, composer area) run to the screen edges
 * although the chat column keeps a [horizontal] content padding.
 */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = extra * 2))
    layout(placeable.width - extra * 2, placeable.height) {
        placeable.place(-extra, 0)
    }
}

/**
 * Last timeline row while a turn runs without visible progress, so a long
 * tool call or reasoning phase never looks like a stalled chat: Orsik's
 * own [WorkingGlyph], a status line and the elapsed time.
 *
 * While Hermes works the line cycles through playful verbs ("tüftelt",
 * "sortiert", ...) under a soft shimmer; "denkt nach" stays as it is (a real
 * status), and a running tool names the tool ([toolName], redacted). With
 * "Remove animations" the glyph rests, the shimmer is off and the first
 * verb (the plain "Hermes arbeitet…") stays.
 */
@Composable
internal fun TurnActivityRow(activity: TurnActivity, startedAt: Long, toolName: String?) {
    val elapsedSeconds by produceState(0L, startedAt) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - startedAt) / 1_000
            delay(1_000)
        }
    }
    val reducedMotion = rememberReducedMotion()
    val verbs = stringArrayResource(R.array.chat_turn_working_verbs)
    var verbIndex by remember(startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(activity, reducedMotion, startedAt) {
        if (activity != TurnActivity.Working || reducedMotion) {
            verbIndex = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(VERB_INTERVAL_MS)
            verbIndex = (verbIndex + 1) % verbs.size
        }
    }
    val label = when (activity) {
        TurnActivity.Working -> verbs[verbIndex]
        TurnActivity.Thinking -> stringResource(R.string.chat_turn_thinking)
        TurnActivity.Tool -> toolName?.let { stringResource(R.string.chat_turn_tool_named, it) }
            ?: stringResource(R.string.chat_turn_tool)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkingGlyph()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(brush = shimmerBrush(reducedMotion)),
            modifier = Modifier.padding(start = 10.dp)
        )
        Text(
            text = if (elapsedSeconds < 60) {
                stringResource(R.string.chat_turn_elapsed_seconds, elapsedSeconds)
            } else {
                stringResource(
                    R.string.chat_turn_elapsed_minutes,
                    elapsedSeconds / 60,
                    elapsedSeconds % 60
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** A light band sweeping over the status text; plain text colour without animations. */
@Composable
private fun shimmerBrush(reducedMotion: Boolean): Brush {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    if (reducedMotion) return SolidColor(base)
    val highlight = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "turnShimmer")
    val x by transition.animateFloat(
        initialValue = -SHIMMER_WIDTH_PX,
        targetValue = SHIMMER_TRAVEL_PX,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
        label = "turnShimmerX"
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + SHIMMER_WIDTH_PX, 0f)
    )
}

private const val VERB_INTERVAL_MS = 2_800L
private const val SHIMMER_WIDTH_PX = 220f
private const val SHIMMER_TRAVEL_PX = 900f

/**
 * First timeline row while the server has older history: a button that loads
 * the previous page, a spinner while it loads, and a short note above the
 * button when the last attempt failed (the visible history stays).
 */
@Composable
private fun LoadOlderRow(loading: Boolean, failed: Boolean, onLoadOlder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TAG_LOAD_OLDER),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            if (failed) {
                Text(
                    text = stringResource(R.string.chat_load_older_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onLoadOlder) {
                Text(text = stringResource(R.string.chat_load_older))
            }
        }
    }
}

@Composable
internal fun TurnFailureBlock(
    error: ChatStreamError,
    showRefreshHistory: Boolean,
    onRefreshHistory: () -> Unit
) {
    val hint = when (error) {
        ChatStreamError.Network,
        ChatStreamError.Timeout ->
            stringResource(R.string.sessions_error_hint_unreachable)
        ChatStreamError.Tls ->
            stringResource(R.string.sessions_error_hint_tls)
        ChatStreamError.InvalidCredentials ->
            stringResource(R.string.sessions_error_hint_credentials)
        ChatStreamError.RateLimited ->
            stringResource(R.string.sessions_error_hint_rate_limited)
        ChatStreamError.IncompatibleApi ->
            stringResource(R.string.sessions_error_hint_incompatible_api)
        else -> null
    }
    NoticeCard(
        tone = NoticeTone.Error,
        title = when (error) {
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
            ChatStreamError.ServerError,
            ChatStreamError.ServerUnavailable,
            ChatStreamError.InvalidInput,
            ChatStreamError.Conflict,
            ChatStreamError.ResumeUnavailable,
            ChatStreamError.ResumeGap ->
                stringResource(R.string.error_connection_http)
        },
        text = hint,
        secondaryText = if (showRefreshHistory) {
            stringResource(R.string.chat_turn_failure_refresh_hint)
        } else {
            stringResource(R.string.chat_turn_failure_draft_kept)
        },
        actions = if (showRefreshHistory) {
            listOf(
                NoticeAction(
                    label = stringResource(R.string.chat_refresh_history),
                    onClick = onRefreshHistory
                )
            )
        } else {
            emptyList()
        },
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * The generic notice shown when a send was rejected because another run
 * already owns the app-wide run slot. It carries no identifier and no server
 * detail - exactly one fixed, understandable sentence.
 */
@Composable
private fun ConflictBlock() {
    Text(
        text = stringResource(R.string.chat_run_conflict),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TAG_RUN_CONFLICT)
    )
}

/**
 * The honest recovery notice for a run whose server-side outcome is
 * unknown. It never presents a failed connection as a stopped run and never
 * claims success. A manual reconciliation action is offered only when a
 * server-confirmed run ID exists ([canReconcile]); without one the notice
 * states the limitation instead of simulating a release.
 */
@Composable
internal fun RunUncertainBlock(
    inFlight: Boolean,
    canReconcile: Boolean,
    stillActive: Boolean,
    unavailable: Boolean,
    onReconcile: () -> Unit
) {
    NoticeCard(
        tone = NoticeTone.Error,
        title = stringResource(R.string.chat_run_uncertain_title),
        text = when {
            stillActive -> stringResource(R.string.chat_run_uncertain_still_active)
            unavailable -> stringResource(R.string.chat_run_uncertain_unavailable)
            else -> stringResource(R.string.chat_run_uncertain)
        },
        actions = if (canReconcile) {
            listOf(
                NoticeAction(
                    label = if (inFlight) {
                        stringResource(R.string.chat_run_uncertain_checking)
                    } else {
                        stringResource(R.string.chat_run_uncertain_check)
                    },
                    onClick = onReconcile,
                    enabled = !inFlight,
                    testTag = TAG_RUN_RECONCILE
                )
            )
        } else {
            emptyList()
        },
        modifier = Modifier
            .padding(vertical = 8.dp)
            .testTag(TAG_RUN_UNCERTAIN)
    )
}

/**
 * The closed status lines of the stop and steer actions: the generic stop
 * failure, and "steering", "queued" or the generic steer failure. No dynamic
 * server detail and no run ID is ever rendered.
 */
@Composable
private fun RunStatusLines(
    showStopError: Boolean,
    stopErrorLabel: String,
    steerInFlight: Boolean,
    steerQueued: Boolean,
    steerFailed: Boolean,
    queuedLabel: String,
    errorLabel: String,
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
        if (steerInFlight) {
            Text(
                text = stringResource(R.string.chat_steering),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("chatSteeringStatus")
            )
        } else if (steerQueued) {
            Text(
                text = queuedLabel,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("chatSteerQueuedStatus")
            )
        } else if (steerFailed) {
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
 * Stop and steer of a running, server-confirmed turn. While it is set, the
 * normal composer row turns into the steer input with a steer action and a
 * round stop action, so a run needs no extra rows above the composer.
 */
internal class RunComposerControls(
    val steerDraft: String,
    val onSteerDraftChange: (String) -> Unit,
    val steerInFlight: Boolean,
    val onSteer: () -> Unit,
    val stopInFlight: Boolean,
    val onStop: () -> Unit
)

/**
 * One ordered block for a single recoverable steer. The title carries the
 * `index/total` counter only when more than one item is preserved; [text] is
 * the exact preserved item, rendered in quotes. The single [actionLabel]
 * action moves exactly THIS one item into the ordinary composer; it never
 * sends it and is disabled - and a no-op in the view model - while [enabled]
 * is false (i.e. the ordinary composer is nonblank), so it can never overwrite
 * the user's in-progress message. While disabled, [clearDraftHint] explains
 * how to enable it again.
 */
@Composable
internal fun SteerRecoveryBlock(
    index: Int,
    total: Int,
    text: String,
    actionLabel: String,
    clearDraftHint: String,
    enabled: Boolean,
    onSendAsNewMessage: () -> Unit,
    testTag: String,
    actionTestTag: String,
    modifier: Modifier = Modifier
) {
    NoticeCard(
        tone = NoticeTone.Info,
        title = if (total > 1) {
            stringResource(R.string.chat_steer_recovery_title_counter, index + 1, total)
        } else {
            stringResource(R.string.chat_steer_recovery_title)
        },
        text = stringResource(R.string.chat_steer_recovery_quote, text),
        textTestTag = "chatSteerRecoveryText",
        secondaryText = if (enabled) null else clearDraftHint,
        actions = listOf(
            NoticeAction(
                label = actionLabel,
                onClick = onSendAsNewMessage,
                enabled = enabled,
                testTag = actionTestTag
            )
        ),
        modifier = modifier.testTag(testTag)
    )
}

@Composable
internal fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    turnActive: Boolean,
    attachment: ChatAttachmentDraft?,
    attachmentThumbnail: ImageBitmap?,
    attachmentBusy: Boolean,
    attachmentErrorLabel: String?,
    attachmentAddLabel: String,
    attachmentRemoveLabel: String,
    attachmentBusyLabel: String,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    runControls: RunComposerControls? = null,
    conflictNotice: Boolean = false,
    uncertainNotice: Boolean = false,
    compactRunControls: (@Composable RowScope.() -> Unit)? = null
) {
    // The tag wraps the whole bar including its own bottom padding, so
    // tests can compare the bar's bottom edge with the content area.
    if (compact) {
        Column(
            modifier = modifier
                .testTag(TAG_COMPOSER_BAR)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            CompactComposerContent(
                draft = draft,
                onDraftChange = onDraftChange,
                sendEnabled = sendEnabled,
                onSend = onSend,
                turnActive = turnActive,
                attachment = attachment,
                attachmentThumbnail = attachmentThumbnail,
                attachmentBusy = attachmentBusy,
                attachmentErrorLabel = attachmentErrorLabel,
                attachmentAddLabel = attachmentAddLabel,
                attachmentRemoveLabel = attachmentRemoveLabel,
                attachmentBusyLabel = attachmentBusyLabel,
                onPickAttachment = onPickAttachment,
                onRemoveAttachment = onRemoveAttachment,
                conflictNotice = conflictNotice,
                uncertainNotice = uncertainNotice,
                compactRunControls = compactRunControls
            )
        }
        return
    }
    BottomDockArea(
        modifier = modifier
            .testTag(TAG_COMPOSER_BAR)
            .padding(top = 8.dp)
    ) {
        // Compact attachment chip above the input row. Local-only data:
        // the file name and size the provider reported, plus a bounded decoded
        // thumbnail; no path, no URI and no provider text is ever rendered.
        if (attachmentErrorLabel != null && attachment != null) {
            Text(
                text = attachmentErrorLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (attachment != null || attachmentErrorLabel != null || attachmentBusy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    attachmentBusy -> {
                        Text(
                            text = attachmentBusyLabel,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    attachment != null -> {
                        attachmentThumbnail?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(end = 8.dp)
                                    .testTag(TAG_ATTACHMENT_THUMBNAIL)
                            )
                        }
                        val sizeLabel = formatAttachmentSize(attachment.sizeBytes)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (sizeLabel.isNotEmpty()) {
                                Text(
                                    text = sizeLabel,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        TextButton(
                            onClick = onRemoveAttachment,
                            enabled = !turnActive,
                            modifier = Modifier.testTag(TAG_ATTACHMENT_REMOVE)
                        ) {
                            Text(attachmentRemoveLabel)
                        }
                    }
                    else -> {
                        Text(
                            text = attachmentErrorLabel.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        // One rounded, filled input bar: the attachment action on the left,
        // the pill-shaped message field in the middle, and a round filled
        // send action on the right. Every action keeps at least a 48 dp touch
        // target; the labels remain available as content descriptions.
        // While a run is bound, the run row below replaces it; the disabled
        // draft row stays only when it holds text (e.g. a recovered steer
        // moved into the composer), so that text never disappears.
        if (runControls == null || draft.isNotBlank()) {
            AgentComposerDock(
                draft = draft,
                onDraftChange = onDraftChange,
                hint = stringResource(R.string.chat_input_label),
                enabled = !turnActive,
                canSend = sendEnabled,
                sending = false,
                onSend = onSend,
                agentSelector = false,
                attachEnabled = !turnActive && !attachmentBusy,
                onAttach = onPickAttachment,
                attachLabel = attachmentAddLabel,
                sendLabel = stringResource(R.string.chat_send),
                maxLines = Int.MAX_VALUE,
                inputTestTag = TAG_COMPOSER_FIELD,
                sendTestTag = TAG_COMPOSER_SEND,
                attachTestTag = TAG_ATTACHMENT_ADD
            )
        }
        if (runControls != null) {
            RunComposerRow(runControls, modifier = Modifier.padding(top = if (draft.isNotBlank()) 8.dp else 0.dp))
        }
    }
}

/**
 * The filled, borderless message field. The shape is a pill and the Material
 * text-field indicator is transparent, so the field has no outline in light or
 * dark mode; [maxLines] is supplied by the normal or compact composer.
 */
@Composable
private fun ChatInputField(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.chat_input_label)
) {
    TextField(
        value = draft,
        onValueChange = onDraftChange,
        label = { Text(label) },
        enabled = enabled,
        maxLines = maxLines,
        shape = RoundedCornerShape(COMPOSER_INPUT_CORNER),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
    )
}

/** The round stop action of a running turn (cookie shape, like send); a spinner while stopping. */
@Composable
private fun StopButton(stopInFlight: Boolean, label: String, onStop: () -> Unit) {
    FilledIconButton(
        onClick = onStop,
        enabled = !stopInFlight,
        shape = ExpressiveShape.Cookie.shape,
        modifier = Modifier
            .size(COMPOSER_ACTION_SIZE)
            .semantics { contentDescription = label }
            .testTag(TAG_RUN_STOP)
    ) {
        if (stopInFlight) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(LocalContentColor.current, RoundedCornerShape(2.dp))
            )
        }
    }
}

/**
 * The composer row of a running turn: the steer input in the pill field, a
 * steer action and the round stop action. Stop stays visible and enabled
 * until a stop request is in flight; it then shows a spinner.
 */
@Composable
private fun RunComposerRow(controls: RunComposerControls, modifier: Modifier = Modifier) {
    val stopLabel = stringResource(
        if (controls.stopInFlight) R.string.chat_stopping else R.string.chat_stop
    )
    AgentComposerDock(
        draft = controls.steerDraft,
        onDraftChange = controls.onSteerDraftChange,
        hint = stringResource(R.string.chat_steer_input_label),
        enabled = !controls.steerInFlight,
        canSend = !controls.steerInFlight,
        sending = false,
        onSend = controls.onSteer,
        agentSelector = false,
        maxLines = Int.MAX_VALUE,
        inputTestTag = "chatSteerInput",
        modifier = modifier,
        trailing = {
            IconButton(
                onClick = controls.onSteer,
                enabled = !controls.steerInFlight,
                modifier = Modifier.testTag("chatSteerSend")
            ) {
                Icon(
                    imageVector = OrsikIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_steer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StopButton(stopInFlight = controls.stopInFlight, label = stopLabel, onStop = controls.onStop)
        }
    )
}

/**
 * The compact composer: ONE dense row for a height-constrained window
 * (landscape with the keyboard open, split screen).
 *
 * Two rows do not fit below the header there, and a row squeezed below its
 * Material minimum would shrink the touch targets of the picker, remove and
 * send actions. This row therefore keeps its natural height, so every control
 * in it keeps a full 48 dp touch target, and only secondary information is
 * reduced: no second chip line, a 24 dp thumbnail and a two-line draft cap.
 * The layout is driven by the available height, never by the orientation.
 *
 * [runControls] replaces the ordinary picker/field/send trio while a run is
 * active (stop and steer must stay reachable even though the timeline and its
 * status blocks are not composed in this regime).
 */
@Composable
private fun CompactComposerRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    turnActive: Boolean,
    attachment: ChatAttachmentDraft?,
    attachmentThumbnail: ImageBitmap?,
    attachmentBusy: Boolean,
    attachmentAddLabel: String,
    attachmentRemoveLabel: String,
    attachmentBusyLabel: String,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: () -> Unit,
    runControls: (@Composable RowScope.() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            attachmentBusy -> {
                Text(
                    text = attachmentBusyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            attachment != null -> {
                attachmentThumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(COMPACT_THUMBNAIL_SIZE)
                            .padding(end = 8.dp)
                            .testTag(TAG_ATTACHMENT_THUMBNAIL)
                    )
                }
                // Bounded, NOT weighted: the draft field below is the only
                // weighted child, so it receives all the remaining width. Two
                // weighted children would pin the field to half of it and
                // leave dead space after the send button. The size line is
                // secondary information and is dropped in this regime - the
                // row must first of all keep the field and the actions
                // reachable.
                Text(
                    text = attachment.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = COMPACT_NAME_MAX_WIDTH)
                )
                TextButton(
                    onClick = onRemoveAttachment,
                    enabled = !turnActive,
                    modifier = Modifier.testTag(TAG_ATTACHMENT_REMOVE)
                ) {
                    Text(attachmentRemoveLabel)
                }
            }
            else -> Unit
        }
        if (runControls != null) {
            runControls()
        } else {
            // A turn carries at most one image, so while an attachment is
            // staged the picker has nothing to do and its width belongs to the
            // draft field.
            if (attachment == null && !attachmentBusy) {
                IconButton(
                    onClick = onPickAttachment,
                    enabled = !turnActive,
                    modifier = Modifier.testTag(TAG_ATTACHMENT_ADD)
                ) {
                    Icon(
                        imageVector = OrsikIcons.Plus,
                        contentDescription = attachmentAddLabel
                    )
                }
            }
            ChatInputField(
                draft = draft,
                onDraftChange = onDraftChange,
                enabled = !turnActive,
                // Bounded lines, NATURAL height: the row must keep a
                // deterministic budget, but a fixed height clipped the text as
                // soon as the font scale grew - a single visible line no
                // longer fitted into a 56 dp box. With only a minimum height
                // the field grows with its content instead of clipping it at
                // font scale 1.3; the two-line cap still bounds the row and
                // longer text scrolls inside the field.
                maxLines = COMPACT_DRAFT_MAX_LINES,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = COMPACT_FIELD_HEIGHT)
                    .padding(horizontal = 8.dp)
                    .testTag(TAG_COMPOSER_FIELD)
            )
            FilledIconButton(
                onClick = onSend,
                enabled = sendEnabled,
                shape = ExpressiveShape.Cookie.shape,
                modifier = Modifier
                    .size(COMPOSER_ACTION_SIZE)
                    .testTag(TAG_COMPOSER_SEND)
            ) {
                Icon(
                    imageVector = OrsikIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_send)
                )
            }
        }
    }
}


/**
 * Content of the compact composer (height-constrained window): the status
 * line and the single dense [CompactComposerRow].
 */
@Composable
private fun CompactComposerContent(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    turnActive: Boolean,
    attachment: ChatAttachmentDraft?,
    attachmentThumbnail: ImageBitmap?,
    attachmentBusy: Boolean,
    attachmentErrorLabel: String?,
    attachmentAddLabel: String,
    attachmentRemoveLabel: String,
    attachmentBusyLabel: String,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: () -> Unit,
    conflictNotice: Boolean,
    uncertainNotice: Boolean,
    compactRunControls: (@Composable RowScope.() -> Unit)?
) {
        // Compact regime (height-constrained window, e.g. landscape with
        // the keyboard open): ONE dense row instead of chip row + input
        // row. Two rows cannot fit below the header there, and a row that
        // is squeezed below 48 dp would shrink the touch targets of
        // "Anhang", "Entfernen" and "Senden". The row keeps the Material
        // minimum height, so every control in it keeps a full 48 dp
        // touch target; only secondary information (the size line, the
        // paddings, the visible draft lines) is reduced.
        //
        // The middle region (timeline, status blocks) is not composed in
        // this regime, so the run-conflict notice is surfaced here instead:
        // exactly ONE bounded line above the row, the same slot and style
        // the attachment error already uses, so no new height class and
        // no IME regression is introduced. The conflict takes precedence
        // over the attachment error (it is the reason the send was
        // rejected); both can never be shown as two lines.
        val compactStatusLabel = when {
            uncertainNotice -> stringResource(R.string.chat_run_uncertain_title)
            conflictNotice -> stringResource(R.string.chat_run_conflict)
            else -> attachmentErrorLabel
        }
        if (compactStatusLabel != null) {
            Text(
                text = compactStatusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        CompactComposerRow(
            draft = draft,
            onDraftChange = onDraftChange,
            sendEnabled = sendEnabled,
            onSend = onSend,
            turnActive = turnActive,
            attachment = attachment,
            attachmentThumbnail = attachmentThumbnail,
            attachmentBusy = attachmentBusy,
            attachmentAddLabel = attachmentAddLabel,
            attachmentRemoveLabel = attachmentRemoveLabel,
            attachmentBusyLabel = attachmentBusyLabel,
            onPickAttachment = onPickAttachment,
            onRemoveAttachment = onRemoveAttachment,
            runControls = compactRunControls
        )
}

/** Display-only size text of the attachment chip (no path, no provider text). */
private fun formatAttachmentSize(sizeBytes: Long): String {
    // A provider may not report a size at all: show nothing instead of a
    // meaningless negative number.
    if (sizeBytes <= 0L) return ""
    val kib = 1024L
    val mib = kib * kib
    return when {
        sizeBytes >= mib -> String.format(java.util.Locale.ROOT, "%.1f MiB", sizeBytes.toDouble() / mib)
        sizeBytes >= kib -> String.format(java.util.Locale.ROOT, "%.0f KiB", sizeBytes.toDouble() / kib)
        else -> "$sizeBytes B"
    }
}

internal const val TAG_ATTACHMENT_ADD = "composer_attachment_add"
internal const val TAG_ATTACHMENT_REMOVE = "composer_attachment_remove"
internal const val TAG_ATTACHMENT_THUMBNAIL = "composer_attachment_thumbnail"

/**
 * Available chat-root height below which the header and the composer switch to
 * their compact spacing.
 *
 * The budget in this regime is header + attachment chip + input row and nothing
 * else: with the Gboard landscape keyboard open (686 px of a 1080 px display)
 * about 394 px ≈ 150 dp remain, while the normal spacing needs roughly 190 dp
 * for those three blocks alone. 300 dp sits well below the smallest
 * non-constrained case (a phone in portrait with the keyboard open is about
 * 600 dp) and well above the landscape-with-keyboard case, so the normal layout
 * is untouched wherever it already fits.
 */
internal val COMPACT_COMPOSER_MAX_HEIGHT = 300.dp

/**
 * Visible draft lines in the compact composer. Bounds the intrinsic height of
 * the input row in the height-constrained regime (the rest of the text stays
 * editable and scrolls inside the field).
 */
internal const val COMPACT_DRAFT_MAX_LINES = 2

/** Max width of the attachment name in the compact composer row. */
internal val COMPACT_NAME_MAX_WIDTH = 96.dp

/**
 * Minimum draft-field height in the compact composer row (Material minimum).
 * The field grows beyond it when its content needs more room (raised font
 * scale, a second draft line), so the text is never clipped.
 */
internal val COMPACT_FIELD_HEIGHT = 56.dp

/** Thumbnail edge in the compact composer row. */
internal val COMPACT_THUMBNAIL_SIZE = 24.dp

/** Touch target and visual size of the round send action. */
private val COMPOSER_ACTION_SIZE = 48.dp

/** Corner radius of the pill-shaped message field. */
private val COMPOSER_INPUT_CORNER = 30.dp

/**
 * Maximum distance from the end of the timeline that still counts as
 * "parked at the tail" for tail-follow. A few pixels of scroll slack (for
 * example a rounding remainder) must not stop the view from following a
 * streamed answer.
 */
internal val FOLLOW_TAIL_THRESHOLD = 48.dp

/**
 * Pixel slack for the exact-end scroll. It only absorbs integer rounding:
 * unlike [FOLLOW_TAIL_THRESHOLD] (which classifies whether the user parked at
 * the end), it must never leave part of the last row below the fold.
 */
private const val TAIL_SCROLL_EPSILON_PX = 1

internal const val TAG_TIMELINE = "chat_timeline"

internal const val TAG_LOAD_OLDER = "chat_load_older"

/**
 * True when the final timeline row is visible and its end lies within
 * [thresholdPx] of the viewport end. An empty or not-yet-measured list has
 * no tail to seek, so it reports `true` and the scroll effect stays idle.
 */
private fun LazyListLayoutInfo.isAtTail(thresholdPx: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return true
    return last.index == totalItemsCount - 1 &&
        last.offset + last.size <= viewportEndOffset + thresholdPx
}

/**
 * Scrolls to the very end without animation.
 *
 * A streamed answer can be taller than the viewport, so landing on its item
 * index alone would leave its end below the fold (and would flash back to
 * the row start on every growth). When the last row is already visible the
 * index jump is skipped and only the remaining distance is scrolled;
 * otherwise (initial open, a new row) the last item is requested first.
 */
private suspend fun LazyListState.scrollToTail() {
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0) return
    if (info.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
        scrollToItem(lastIndex)
    }
    // Scroll exactly the residual distance instead of an unbounded delta,
    // so a single immediate scroll reaches the end of the content.
    val after = layoutInfo
    val last = after.visibleItemsInfo.lastOrNull()
    if (last == null || last.index != lastIndex) return
    val remaining = last.offset + last.size - after.viewportEndOffset
    if (remaining > 0) {
        scrollBy(remaining.toFloat())
    }
}

internal const val TAG_CHAT_TITLE = "chat_header_title"
internal const val TAG_RUN_STOP = "composer_run_stop"
internal const val TAG_COMPOSER_BAR = "composer_bar"
internal const val TAG_RUN_CONFLICT = "chat_run_conflict"
internal const val TAG_RUN_UNCERTAIN = "chat_run_uncertain"
internal const val TAG_RUN_RECONCILE = "chat_run_reconcile"
internal const val TAG_COMPOSER_SEND = "composer_send"
internal const val TAG_COMPOSER_FIELD = "composer_field"

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

/** Width caps of the user bubbles, relative to the content width. */
private const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.82f

/** Gap between two timeline messages; split as half above and below. */
private val MESSAGE_VERTICAL_GAP = 12.dp

/** Tail corner on the sender side, rounded everywhere else. */
private val USER_BUBBLE_SHAPE = RoundedCornerShape(
    topStart = 22.dp,
    topEnd = 22.dp,
    bottomEnd = 6.dp,
    bottomStart = 22.dp
)

private val NEUTRAL_BUBBLE_SHAPE = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 4.dp,
    bottomEnd = 18.dp
)

/**
 * User messages sit right-aligned in a brand-tinted bubble; Hermes answers
 * are full-width plain content without a bubble or sender label. System
 * messages keep their neutral bubble and label, tool activity is a compact
 * chip. The visual "You"/"Hermes" labels are gone, but each user and
 * assistant row keeps them as its accessibility label ("You: …" /
 * "Hermes: …").
 */
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
    val isUser = message.role == MessageRole.USER
    val isAssistant = message.role == MessageRole.ASSISTANT
    val isHistoryTool = message.role == MessageRole.TOOL && !message.localTool
    val isLiveTool = message.role == MessageRole.TOOL && message.localTool
    val chatTextScale = LocalChatTextScale.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MESSAGE_VERTICAL_GAP / 2)
            .testTag("chatTimelineRow:${message.key}")
    ) {
        if (isUser || isAssistant) {
            // The sender ("You"/"Hermes") is no longer shown, but a screen
            // reader still announces it once, right before the message text
            // (a 1 dp node: TalkBack skips nodes without size).
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics { contentDescription = role }
            )
        }
        Column(
            modifier = if (isUser) {
                Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(max = maxWidth * USER_BUBBLE_MAX_WIDTH_FRACTION)
            } else {
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
            },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Only the system role keeps a visible sender label; user and
            // assistant roles exist only in the accessibility semantics.
            if (message.role == MessageRole.SYSTEM) {
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp)
                )
            }
            // SECURITY (history tool redaction): a history TOOL row renders
            // ONLY its safe chip label — never the untrusted `content`. The
            // timeline already strips it (see buildTimeline); this keeps the
            // UI fail closed even if a future path ever carried verbatim tool
            // data (commands, paths, output, payloads).
            when {
                isHistoryTool -> ToolActivityChip(
                    label = role,
                    status = stringResource(R.string.chat_tool_done),
                    modifier = Modifier.testTag("chatToolChip:${message.key}")
                )
                isLiveTool -> ToolActivityChip(
                    label = message.content,
                    modifier = Modifier.testTag("chatToolChip:${message.key}")
                )
                isAssistant -> Row(modifier = Modifier.fillMaxWidth()) {
                    // Hermes' sunny "H" marks the answer; the Markdown body
                    // itself has no bubble and reads like a document.
                    AgentMark(size = 30, modifier = Modifier.clearAndSetSemantics {})
                    Spacer(Modifier.width(10.dp))
                    ChatTextScaledContent(chatTextScale) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chatAssistantBody:${message.key}")
                        ) {
                            AssistantMarkdownMessage(
                                content = message.content,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chatMessageText:${message.key}")
                            )
                        }
                    }
                }
                else -> ChatTextScaledContent(chatTextScale) {
                    Box(
                        modifier = Modifier
                            .testTag("chatBubble:${message.key}")
                            .clip(if (isUser) USER_BUBBLE_SHAPE else NEUTRAL_BUBBLE_SHAPE)
                            .background(
                                if (isUser) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column {
                            if (message.imageCount > 0) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.chat_message_images,
                                        message.imageCount,
                                        message.imageCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .padding(bottom = if (message.content.isEmpty()) 0.dp else 4.dp)
                                        .testTag("chatMessageImages:${message.key}")
                                )
                            }
                            if (message.content.isNotEmpty() || message.imageCount == 0) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.testTag("chatMessageText:${message.key}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Several tool calls in a row as one collapsible line: "3 Tools · terminal,
 * read_file +1 · fertig". Expanded, the individual tool chips follow. Only
 * the safe, redacted tool names are shown - never content or arguments.
 */
@Composable
private fun ToolGroupRow(
    group: TimelineItem.ToolGroup,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val names = group.names
    val count = pluralStringResource(R.plurals.chat_tool_group_count, group.rows.size, group.rows.size)
    val shown = names.take(2).joinToString(", ")
    val more = (names.size - 2).takeIf { it > 0 }?.let { stringResource(R.string.chat_tool_group_more, it) }
    val summary = listOfNotNull(count, listOfNotNull(shown.takeIf { it.isNotEmpty() }, more).joinToString(" ").takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    val status = stringResource(if (running) R.string.chat_tool_running else R.string.chat_tool_done)
    val toggleLabel = stringResource(if (expanded) R.string.chat_tool_group_collapse else R.string.chat_tool_group_expand)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MESSAGE_VERTICAL_GAP / 2)
            .testTag("chatToolGroup:${group.key}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("chatToolGroupToggle:${group.key}")
        ) {
            Icon(
                imageVector = OrsikIcons.Wrench,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$summary · $status",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = OrsikIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, top = 6.dp)
            ) {
                group.rows.forEach { row -> MessageRow(row) }
            }
        }
    }
}

/**
 * Compact, static tool-activity chip: wrench, the safe label and - for a
 * finished history tool - its [status] ("fertig").
 */
@Composable
internal fun ToolActivityChip(
    label: String,
    modifier: Modifier = Modifier,
    status: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = OrsikIcons.Wrench,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status != null) {
            Text(
                text = " · $status",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
