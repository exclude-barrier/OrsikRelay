package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.ui.theme.brandSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat screen with the live turn slice (Mission 006A).
 *
 * [chatSource] is the test seam: production injects the repository via
 * [io.github.excludebarrier.orsikrelay.navigation.HermesNavHost], Compose tests inject
 * synthetic fakes. The screen depends only on the closed [ChatTurnEvent]
 * set; no tool name, argument, preview, reasoning or payload ever enters
 * the UI state.
 *
 * Rules:
 * - the composer is only visible after the history loaded successfully;
 *   a successfully loaded but empty history (and no local turn lines)
 *   renders [R.string.chat_empty] while the composer stays visible;
 * - blank or invalid input (length, control characters) cannot be sent;
 *   the send button is visible but disabled for a blank draft;
 * - during a turn the field and the send button are disabled; a fast
 *   double tap starts exactly one stream; there is never an automatic
 *   retry;
 * - the local user line appears immediately after an accepted submit and
 *   the draft is cleared only once `RunStarted` is received; a failure
 *   before that keeps the draft and removes the premature user line;
 * - submit/send is blocked only during an active turn or after a failure
 *   that happened after the confirmed start;
 * - `AssistantStarted` creates exactly one temporary assistant line;
 *   deltas grow it; `AssistantCompleted` replaces it with the
 *   authoritative final text; `TurnCompleted` frees the composer;
 * - a flow that ends without `TurnCompleted` or `TurnFailed` is treated
 *   as a generic network failure, never as a silent success;
 * - a failure after the run started shows only a generic message plus a
 *   manual "refresh history" action that resets the ephemeral local
 *   lines, re-runs the existing idempotent history read and never
 *   resends the POST;
 * - back during a running turn waits for the collector cancellation
 *   (`cancelAndJoin`) on the main scope before invoking [onBack] exactly
 *   once; fast double taps are ignored while that is in flight;
 * - leaving the screen (or a profile/session switch) cancels the
 *   collector without compose state writes in the disposal callback:
 *   the keyed `remember` slots reset every ephemeral state on a switch,
 *   and the collector's `finally` clears its own bookkeeping on the
 *   main scope;
 * - timeline keys are stable, collision-free strings per screen:
 *   `server:<Long-ID>` for history rows and `local:<per-screen counter>`
 *   for ephemeral turn lines.
 */
@Composable
fun ChatScreen(
    historySource: MessageHistorySource,
    chatSource: StreamingChatSource,
    profileId: String,
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var state by remember(profileId, sessionId) {
        mutableStateOf<MessageHistoryState>(MessageHistoryState.Loading)
    }
    var loadInFlight by remember(profileId, sessionId) { mutableStateOf(false) }
    var draft by remember(profileId, sessionId) { mutableStateOf("") }
    var localLines by remember(profileId, sessionId) {
        mutableStateOf<List<TimelineRow>>(emptyList())
    }
    var turnFailure by remember(profileId, sessionId) { mutableStateOf<ChatStreamError?>(null) }
    var turnActive by remember(profileId, sessionId) { mutableStateOf(false) }
    var runStarted by remember(profileId, sessionId) { mutableStateOf(false) }
    var backPending by remember(profileId, sessionId) { mutableStateOf(false) }
    var localKeyCounter by remember(profileId, sessionId) { mutableIntStateOf(0) }
    val timelineListState = remember(profileId, sessionId) { LazyListState() }
    val toolActivityLabel = stringResource(R.string.chat_tool_activity)

    // Not keyed by profile/session: a switch disposes the old effect
    // after the keyed slots above are already reset, but the dispose
    // callback must still reach the collector of the previous session.
    // The holder itself resets when the screen leaves composition.
    val turnRuntime = remember { TurnRuntime() }

    fun nextLocalKey(): String {
        localKeyCounter += 1
        return "local:$localKeyCounter"
    }

    fun loadHistory() {
        if (loadInFlight) return
        loadInFlight = true
        state = MessageHistoryState.Loading
        turnFailure = null
        coroutineScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    historySource.loadHistory(profileId, sessionId)
                }
            } catch (error: CancellationException) {
                // Rethrown after the `finally` below released the guard;
                // no state is published after cancellation.
                throw error
            } catch (_: Exception) {
                MessageHistoryResult.Failure(MessageHistoryError.Network)
            } finally {
                // Release the in-flight guard on the main scope BEFORE
                // the finished Ready/Failed state is published below:
                // Compose must never see a tappable retry/refresh button
                // while the guard still swallows the next load.
                loadInFlight = false
            }
            state = when (result) {
                is MessageHistoryResult.Success -> MessageHistoryState.Ready(result.page.messages)
                is MessageHistoryResult.Failure -> MessageHistoryState.Failed(result.error)
            }
        }
    }

    LaunchedEffect(profileId, sessionId) {
        loadHistory()
    }

    fun submit() {
        if (turnActive) return
        if (state !is MessageHistoryState.Ready) return
        // A failure before the confirmed start does not block a new,
        // deliberate submit; only an active turn or a failure after the
        // confirmed start does.
        if (turnFailure != null && runStarted) return
        val message = ChatMessageValidator.validate(draft).getOrNull() ?: return
        val text = draft
        turnActive = true
        turnFailure = null
        runStarted = false
        // The local user line appears immediately after the accepted
        // submit; the draft is cleared only on `RunStarted`.
        val userLineKey = nextLocalKey()
        localLines = localLines + TimelineRow(
            key = userLineKey,
            role = MessageRole.USER,
            content = message
        )

        fun dropPrematureUserLine() {
            if (!runStarted) {
                // Failure before confirmed `run.started`: drop the
                // premature local user line and keep the draft in the
                // composer.
                localLines = localLines.filter { it.key != userLineKey }
            }
        }

        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            // The collector's own job (the local initializer cannot be
            // referenced from inside its own lambda).
            val self: Job? = coroutineContext[Job]
            var completed = false
            var cancelled = false
            try {
                chatSource.streamTurn(profileId, sessionId, text).takeWhile { event ->
                    when (event) {
                        is ChatTurnEvent.RunStarted -> {
                            runStarted = true
                            draft = ""
                        }
                        is ChatTurnEvent.AssistantStarted -> {
                            localLines = localLines + TimelineRow(
                                key = nextLocalKey(),
                                role = MessageRole.ASSISTANT,
                                content = ""
                            )
                        }
                        is ChatTurnEvent.AssistantDelta -> {
                            localLines = appendToLastAssistantLine(localLines, event.text)
                        }
                        is ChatTurnEvent.AssistantCompleted -> {
                            localLines = replaceLastAssistantLine(
                                localLines,
                                event.finalText
                            )
                        }
                        is ChatTurnEvent.ToolActivity -> {
                            // Generic status only: never a tool name or
                            // payload (those exist only for server
                            // history lines).
                            localLines = localLines + TimelineRow(
                                key = nextLocalKey(),
                                role = MessageRole.TOOL,
                                content = toolActivityLabel,
                                localTool = true
                            )
                        }
                        is ChatTurnEvent.TurnCompleted -> {
                            completed = true
                        }
                        is ChatTurnEvent.TurnFailed -> {
                            turnFailure = event.error
                            dropPrematureUserLine()
                        }
                    }
                    event !is ChatTurnEvent.TurnCompleted &&
                        event !is ChatTurnEvent.TurnFailed
                }.collect { }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (_: Exception) {
                turnFailure = ChatStreamError.Network
                dropPrematureUserLine()
            } finally {
                // A flow that ends without `TurnCompleted` or
                // `TurnFailed` must not silently release the composer:
                // a closed stream is a generic network failure, and
                // there is never an automatic retry.
                if (!cancelled && !completed && turnFailure == null) {
                    turnFailure = ChatStreamError.Network
                    dropPrematureUserLine()
                }
                // Bookkeeping is cleared here, on the main scope — never
                // from a foreign `invokeOnCompletion` thread.
                if (turnRuntime.job === self) {
                    turnRuntime.job = null
                    turnActive = false
                    backPending = false
                }
            }
        }
        // Register the job BEFORE it starts: with a synchronous source
        // the collector could finish inside `start()`, and the `finally`
        // block must still find its own job to release `turnActive`.
        turnRuntime.job = job
        job.start()
    }

    // A profile/session switch (or leaving composition) cancels the
    // running collector. No compose state writes happen in the disposal
    // callback: the keyed `remember` slots above reset every ephemeral
    // state on a switch, and the collector's `finally` clears its own
    // bookkeeping on the main scope.
    DisposableEffect(profileId, sessionId) {
        onDispose {
            turnRuntime.job?.cancel()
        }
    }

    fun handleBack() {
        when {
            !turnActive -> onBack()
            backPending -> Unit
            else -> {
                // Back during a running turn (button, system key or
                // gesture): wait for the collector cancellation on the
                // main scope (which closes the repository/client
                // handle) before [onBack] is invoked exactly once; fast
                // double taps are ignored while that is in flight.
                backPending = true
                coroutineScope.launch {
                    turnRuntime.job?.cancelAndJoin()
                    turnActive = false
                    runStarted = false
                    localLines = emptyList()
                    turnFailure = null
                    draft = ""
                    onBack()
                }
            }
        }
    }

    // System and gesture back (Mission 006A): the platform back event
    // must go through exactly the same path as the visible button, so
    // a running stream is always canceled before the navigation back to
    // the session list happens.
    BackHandler(enabled = true, onBack = { handleBack() })

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { handleBack() }
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

        when (val current = state) {
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
                    onClick = ::loadHistory,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.sessions_retry))
                }
            }

            is MessageHistoryState.Ready -> {
                val timeline = buildTimeline(current.messages, localLines)
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
                val failure = turnFailure
                if (failure != null) {
                    TurnFailureBlock(
                        error = failure,
                        showRefreshHistory = runStarted,
                        onRefreshHistory = {
                            // Reset the ephemeral local lines, the turn
                            // failure and the start flag before the
                            // idempotent history read so the freshly
                            // loaded server history is not rendered next
                            // to local duplicates. The POST is never
                            // resent, and the in-flight guard is left
                            // untouched: only `loadHistory()` releases
                            // it, once, on the main scope.
                            localLines = emptyList()
                            turnFailure = null
                            runStarted = false
                            loadHistory()
                        }
                    )
                }
                ComposerBar(
                    draft = draft,
                    onDraftChange = { if (!turnActive) draft = it },
                    sendEnabled = !turnActive &&
                        (turnFailure == null || !runStarted) &&
                        ChatMessageValidator.validate(draft).isSuccess,
                    onSend = { submit() },
                    turnActive = turnActive
                )
            }
        }
    }
}

/**
 * Holder for the running turn collector. It is deliberately not keyed by
 * profile/session (see [ChatScreen]) so a disposed effect can still
 * cancel the collector of the previous session.
 */
private class TurnRuntime {
    var job: Job? = null
}

/**
 * Closed UI timeline row: the small, separate UI-only type that unifies
 * server history messages and ephemeral local turn lines. Server
 * messages are mapped here (no fake domain ID is forced into the server
 * model); local lines carry their own per-screen counter.
 */
internal data class TimelineRow(
    val key: String,
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
    val localTool: Boolean = false
)

private fun buildTimeline(
    history: List<HermesChatMessage>,
    local: List<TimelineRow>
): List<TimelineRow> =
    history.map { message ->
        TimelineRow(
            key = "server:${message.id}",
            role = message.role,
            content = message.content,
            toolName = message.toolName
        )
    } + local

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
                ChatStreamError.Conflict ->
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
            // Ephemeral live tool activity: generic status only, never a
            // tool name (those exist only for server history lines).
            stringResource(R.string.chat_role_tool)
        } else {
            message.toolName?.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.chat_role_tool_named, it)
            } ?: stringResource(R.string.chat_role_tool)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .brandSurface(background = bubbleBackground, contentPadding = 14.dp)
        ) {
            if (message.content.isNotEmpty()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

private fun appendToLastAssistantLine(
    lines: List<TimelineRow>,
    delta: String
): List<TimelineRow> {
    val index = lines.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (index < 0) return lines
    val targetKey = lines[index].key
    return lines.map { line ->
        if (line.key == targetKey) {
            line.copy(content = line.content + delta)
        } else {
            line
        }
    }
}

private fun replaceLastAssistantLine(
    lines: List<TimelineRow>,
    finalText: String
): List<TimelineRow> {
    val index = lines.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (index < 0) return lines
    val targetKey = lines[index].key
    return lines.map { line ->
        if (line.key == targetKey) {
            line.copy(content = finalText)
        } else {
            line
        }
    }
}
