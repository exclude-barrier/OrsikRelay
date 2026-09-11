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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
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
 *
 * Run-stop slice (Mission 007B): [runStopRequester] is the small
 * domain seam; production injects the repository via
 * [io.github.excludebarrier.orsikrelay.navigation.HermesNavHost], Compose tests
 * inject synthetic fakes. The stop control is only visible while a
 * turn is active AND a non-blank server-confirmed run ID from
 * `RunStarted` is bound; no stop POST is possible before `run.started`.
 * The first tap sends exactly one stop request; the control is then
 * disabled with the "stopping" label. `Accepted` only marks that the
 * stop was accepted: no optimistic terminal state, the composer stays
 * blocked and the stream is never interrupted locally — the existing
 * terminal stream state ends the turn. `AlreadyTerminal` is an
 * idempotent success that triggers only the existing history
 * reconciliation path and never resends the chat POST. `Failure` shows
 * one closed, generic message (no dynamic server detail, no run ID),
 * keeps the running stream collection untouched and allows one
 * deliberate manual retry. Back, disposal and a profile/session switch
 * cancel a running stop coroutine; a late callback can never mutate a
 * left or switched screen. The run ID is bound per screen and cleared
 * on submit and on every terminal reset; it never enters a log,
 * error message or test diagnostic.
 *
 * Run-steer slice (Mission 008B): [runSteerRequester] is the small
 * domain seam; production injects the repository via
 * [io.github.excludebarrier.orsikrelay.navigation.HermesNavHost], Compose tests
 * inject synthetic fakes. The steer input and its own "steer" action
 * are visible only while a turn is active AND the same server-confirmed,
 * non-blank run ID is bound — they are distinct from, and never
 * overwrite, the ordinary new-message composer. Submit validates the
 * text, opens exactly one steer request (fast multi-taps never open a
 * second one) and sends the bound run ID plus the exact trimmed user
 * text. `Queued` only means queued: a concise acknowledgement is shown,
 * the steer input is cleared and the run stays active — nothing terminal
 * is set locally. `NotAccepting` (409) preserves the exact text in the
 * recoverable state and offers one explicit "send as new message"
 * action; the text is moved into the ordinary composer, never sent
 * automatically. `Failure` shows one closed, generic message (no
 * dynamic server detail), keeps the text in the steer input for one
 * deliberate manual retry and never discloses raw server data. The
 * server's `pending_steer` on `run.completed` lands in the same
 * recoverable state through the identical action.
 *
 * Recoverable steers are modelled as SEPARATE, ORDERED items, never as
 * one merged string: two distinct steers (even with identical text)
 * stay two items and are each moved into the ordinary composer one at
 * a time. Each displayed item carries its OWN "send as new message"
 * action, wired to that item's exact position, so tapping one block
 * moves exactly THAT item — never the first one — and every block action
 * has a unique, stable test tag (the shared label text alone is
 * ambiguous in Compose selectors). The explicit action is disabled —
 * and a defensive no-op — while the ordinary composer already holds a
 * nonblank draft, so it can never overwrite the user's in-progress
 * message. A new submit, every terminal reset, back and a profile/
 * session switch clear the run-specific steer state (including the
 * in-flight submitted-text tracking); the recoverable items are
 * preserved whenever clearing them would lose user input.
 * Back and disposal cancel a running steer request; a late callback can
 * never mutate a left or switched screen.
 *
 * Composer-draft slice (Mission 009A): [chatDraftRepository] is the small
 * data seam that persists ONLY the ordinary new-message [draft], keyed by
 * the (profileId, sessionId) pair. It restores the exact stored draft for
 * the active key after an Activity/config recreation or when returning to
 * the same session; a keyed load effect cancels a stale, delayed old-key
 * load before it can apply to the new screen, so text can never leak across
 * keys. Every draft change schedules one debounced latest-value write (no
 * unbounded write per keystroke); leaving the screen or switching keys
 * deterministically flushes the pending write for the key being left. The
 * stored draft is cleared exactly where the composer is cleared on a
 * successful send (`RunStarted`); a blocked or failed submit — and any
 * failure before the confirmed start — keeps both the composer text and the
 * persisted draft. Run/stop/steer input, recovery items, run IDs,
 * credentials, URLs and transient errors are never modelled by this seam
 * and can never be persisted or restored through it.
 */
@Composable
fun ChatScreen(
    historySource: MessageHistorySource,
    chatSource: StreamingChatSource,
    runStopRequester: RunStopRequester,
    runSteerRequester: RunSteerRequester,
    chatDraftRepository: ChatDraftRepository,
    runApprovalRequester: RunApprovalRequester = NoOpRunApprovalRequester,
    approvalUiEnabled: Boolean = false,
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
    var draft by rememberSaveable(profileId, sessionId) { mutableStateOf("") }
    // Saveable and key-bound: a non-zero generation means this screen key
    // already has newer local/restored intent, so an Activity recreation must
    // not let an older disk value overwrite the Bundle-restored composer.
    var draftGeneration by rememberSaveable(profileId, sessionId) { mutableIntStateOf(0) }
    var localLines by remember(profileId, sessionId) {
        mutableStateOf<List<TimelineRow>>(emptyList())
    }
    var turnFailure by remember(profileId, sessionId) { mutableStateOf<ChatStreamError?>(null) }
    var turnActive by remember(profileId, sessionId) { mutableStateOf(false) }
    var runStarted by remember(profileId, sessionId) { mutableStateOf(false) }
    // The server-confirmed run ID of the active turn (Mission 007B).
    // Bound from `RunStarted`, cleared on submit and on every terminal
    // reset; never rendered, logged or embedded in an error message.
    var boundRunId by remember(profileId, sessionId) { mutableStateOf<String?>(null) }
    // Never persisted: a pending decision belongs only to this active SSE
    // turn and must disappear on navigation, recreation or terminal state.
    var pendingApproval by remember(profileId, sessionId) { mutableStateOf<ApprovalRequest?>(null) }
    // True while exactly one stop request is in flight.
    var stopInFlight by remember(profileId, sessionId) { mutableStateOf(false) }
    // True after a closed, generic stop failure: the control re-enables
    // for one deliberate manual retry and shows the generic message.
    var stopFailed by remember(profileId, sessionId) { mutableStateOf(false) }
    // Steer draft (Mission 008B): separate from the new-message [draft];
    // the steer input is only visible for an active, run-bound turn.
    var steerDraft by remember(profileId, sessionId) { mutableStateOf("") }
    // True while exactly one steer request is in flight.
    var steerInFlight by remember(profileId, sessionId) { mutableStateOf(false) }
    // True after a `Queued` answer: the concise acknowledgement is shown
    // until the next steer attempt or the terminal reset.
    var steerQueued by remember(profileId, sessionId) { mutableStateOf(false) }
    // True after a closed, generic steer failure: the text stays in the
    // steer input for one deliberate manual retry.
    var steerFailed by remember(profileId, sessionId) { mutableStateOf(false) }
    // Recoverable steers (Mission 008B): a steer the server did not queue
    // (`NotAccepting`) or a `pending_steer` carried by `run.completed`.
    // Modelled as SEPARATE, ORDERED items — never merged into one string,
    // so each original user steer keeps its own identity. Each item is only
    // ever moved into the ordinary composer by one explicit user action,
    // one item at a time — never sent automatically.
    var steerRecovery by remember(profileId, sessionId) { mutableStateOf<List<String>>(emptyList()) }
    // The exact, validated/trimmed steer text that was actually submitted
    // for the currently in-flight `steerRun` request (Mission 008B). This
    // is the text handed to [RunSteerRequester.steerRun] (already `trim`med),
    // NOT the mutable/raw [steerDraft]. Set only after the request has
    // started; cleared on every result (queued / 409 / failure) and on
    // terminal / session boundaries, so it is always the wire text of the
    // one in-flight request — or null when none is in flight. Used to
    // suppress the same-request terminal duplicate, where comparing against
    // the raw draft would not match a server-normalized `pending_steer`
    // (e.g. input `"  Gleich  "` -> wire `"Gleich"`).
    var steerSubmittedText by remember(profileId, sessionId) { mutableStateOf<String?>(null) }
    var backPending by remember(profileId, sessionId) { mutableStateOf(false) }
    var localKeyCounter by remember(profileId, sessionId) { mutableIntStateOf(0) }
    val timelineListState = remember(profileId, sessionId) { LazyListState() }
    val toolActivityLabel = stringResource(R.string.chat_tool_activity)
    val stopLabel = stringResource(R.string.chat_stop)
    val stoppingLabel = stringResource(R.string.chat_stopping)
    val stopErrorLabel = stringResource(R.string.error_run_stop_generic)
    val steerLabel = stringResource(R.string.chat_steer)
    val steerInputLabel = stringResource(R.string.chat_steer_input_label)
    val steerQueuedLabel = stringResource(R.string.chat_steer_queued)
    val steerErrorLabel = stringResource(R.string.error_run_steer_generic)
    val steerRecoveryLabel = stringResource(R.string.chat_steer_recovery_note)
    val steerSendAsNewMessageLabel = stringResource(R.string.chat_steer_send_as_new_message)

    // Not keyed by profile/session: a switch disposes the old effect
    // after the keyed slots above are already reset, but the dispose
    // callback must still reach the collectors of the previous session.
    // The holder itself resets when the screen leaves composition.
    val turnRuntime = remember { TurnRuntime() }

    fun nextLocalKey(): String {
        localKeyCounter += 1
        return "local:$localKeyCounter"
    }

    /**
     * Appends one recoverable steer (Mission 008B) as its own ordered item.
     * Items are never merged: two occurrences with identical text remain two
     * separate items, each kept intact.
     */
    fun appendSteerRecovery(text: String) {
        steerRecovery = steerRecovery + text
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

    // Restore the persisted ordinary draft for the active key (Mission 009A).
    // Keyed by (profileId, sessionId): on a switch or an Activity/config
    // recreation the previous effect is canceled before this one applies, so
    // a stale, delayed old-key load can never overwrite the new screen's
    // composer. A canceled load throws and applies nothing; a failed load
    // degrades to an empty draft (the repository never throws dynamic
    // detail).
    // Same-key and recreation protection: zero is the only state with no
    // newer local or Bundle-restored intent. Any local mutation bumps the
    // saveable generation, so a delayed disk load cannot overwrite typing,
    // a successful-send clear, or a composer restored by saved instance state.
    LaunchedEffect(profileId, sessionId) {
        val loadedKey = profileId to sessionId
        val generationAtLoadStart = draftGeneration
        val restored = chatDraftRepository.loadDraft(profileId, sessionId)
        if (
            restored != null &&
            loadedKey == (profileId to sessionId) &&
            generationAtLoadStart == 0 &&
            generationAtLoadStart == draftGeneration
        ) {
            draft = restored
            draftGeneration += 1
        }
    }

    /**
     * Records one ordinary-draft change and schedules its debounced,
     * latest-value persist for the active key (Mission 009A). An empty value
     * removes the stored state rather than retaining a blank record.
     */
    fun updateDraft(value: String) {
        draft = value
        // Every local ordinary-draft mutation invalidates in-flight same-key
        // restore loads (see the keyed load effect above): a delayed stored
        // value must never overwrite this newer local intent.
        draftGeneration += 1
        chatDraftRepository.persistDraft(profileId, sessionId, value)
    }

    /**
     * Clears the ordinary draft and its stored state together (Mission
     * 009A). Used exactly where the composer is cleared on a successful send
     * (`RunStarted`) and on a back that leaves a running turn, so the
     * just-sent text is never re-persisted as a draft.
     */
    fun clearDraftState() {
        draft = ""
        // A clear is local intent too: a delayed same-key load must never
        // resurrect the just-sent (or just-cleared) stored value.
        draftGeneration += 1
        chatDraftRepository.clearDraft(profileId, sessionId)
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
        // A new turn starts with a fresh stop state: the previous run ID
        // and any in-flight stop belong to the finished turn and must
        // not leak into this one (Mission 007B).
        boundRunId = null
        stopInFlight = false
        stopFailed = false
        // A new turn also starts with a fresh steer state (Mission 008B):
        // the previous run's steer draft, in-flight request and
        // acknowledgement belong to the finished run and must not leak
        // into this one. The recoverable text is deliberately KEPT: it
        // is user input that is only ever sent after one explicit action
        // ("send as new message"), and a new normal submit must not
        // discard it.
        steerInFlight = false
        steerQueued = false
        steerFailed = false
        steerDraft = ""
        // No steer is in flight for the new turn; the submitted-text
        // tracking must not leak across the turn boundary (Mission 008B).
        steerSubmittedText = null
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
            // The `pending_steer` carried by THIS turn's terminal event, so
            // `finally` can tell "the in-flight request the server already
            // reported as pending" apart from a genuinely separate leftover.
            var completedPendingSteer: String? = null
            try {
                chatSource.streamTurn(profileId, sessionId, text).takeWhile { event ->
                    when (event) {
                        is ChatTurnEvent.RunStarted -> {
                            runStarted = true
                            // The server-confirmed run ID is the only
                            // value the stop control may use; it is
                            // bound here and never rendered or logged.
                            boundRunId = event.runId
                            // Successful send handoff: clear the composer AND
                            // its stored state together (Mission 009A), so
                            // the just-sent text is not re-persisted as a
                            // draft. A failure before `run.started` keeps the
                            // draft (see `dropPrematureUserLine`).
                            clearDraftState()
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
                        is ChatTurnEvent.ApprovalRequested -> {
                            // The pipeline bound both IDs to this exact run
                            // and validated the redacted display fields. One
                            // agent turn waits for at most one gateway prompt.
                            pendingApproval = event.request
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
                            val pending = event.pendingSteer
                            if (pending != null) {
                                // Mission 008B: a `pending_steer` carried
                                // by `run.completed` is surfaced through
                                // the same recoverable state, as its own
                                // separate item — it is only ever sent
                                // after one explicit user action, and it
                                // never merges with or overwrites an
                                // existing recoverable item.
                                completedPendingSteer = pending
                                appendSteerRecovery(pending)
                            }
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
                    if (!cancelled) {
                        // The turn settled: a stop request that is still
                        // in flight belongs to the finished run and is
                        // canceled so no late callback can mutate the
                        // screen state (Mission 007B). The same holds
                        // for a steer request (Mission 008B).
                        turnRuntime.stopJob?.cancel()
                        turnRuntime.steerJob?.cancel()
                    }
                    turnRuntime.stopJob = null
                    turnRuntime.steerJob = null
                    stopInFlight = false
                    stopFailed = false
                    boundRunId = null
                    // The run-specific steer state of the finished run is
                    // stale and is cleared here, on the main scope
                    // (Mission 008B). The recoverable text is kept on
                    // purpose: it is user input that is only ever sent
                    // after one explicit action, and a terminal event
                    // must not lose it. Any steer draft that still holds
                    // user text — whether a request is in flight and was
                    // just canceled (the server may or may not have
                    // queued it), a closed failure awaiting a deliberate
                    // retry, or simply typed but not sent before the run
                    // ended — moves into the recoverable state instead
                    // of being silently discarded.
                    //
                    // Capture the in-flight request's EXACT submitted text
                    // BEFORE clearing the flag: it is only known at this
                    // moment (the flag is still true while the request has
                    // not answered), not at collector start. This is the
                    // validated/trimmed wire text, not the mutable [steerDraft]
                    // (Mission 008B).
                    val inFlightSteerText =
                        if (steerInFlight) steerSubmittedText else null
                    val leftoverSteerDraft = steerDraft.takeIf { it.isNotBlank() }
                    steerInFlight = false
                    steerQueued = false
                    steerFailed = false
                    steerDraft = ""
                    // The request no longer belongs to a live turn; the
                    // submitted-text tracking is cleared at this terminal
                    // boundary too, so it can never leak into the next turn.
                    steerSubmittedText = null
                    // Same-request duplicate suppression (Mission 008B): if
                    // the in-flight request was already reported by this
                    // turn's terminal `pending_steer`, it has been appended
                    // exactly once already and the leftover draft is the same
                    // request, not a second one. The comparison uses the
                    // exact validated/trimmed wire text — never the raw,
                    // untrimmed [steerDraft] — so a server-normalized
                    // `pending_steer` (e.g. `"  Gleich  "` -> `"Gleich"`)
                    // matches. Only that exact case is suppressed; a leftover
                    // draft that differs from the terminal `pending_steer`
                    // (or one that was never reported pending) is a genuinely
                    // separate steer and is appended as its own item. No
                    // global text deduplication.
                    val sameRequestAsTerminalPending =
                        inFlightSteerText != null &&
                            completedPendingSteer != null &&
                            inFlightSteerText == completedPendingSteer
                    if (leftoverSteerDraft != null && !sameRequestAsTerminalPending) {
                        appendSteerRecovery(leftoverSteerDraft)
                    }
                }
            }
        }
        // Register the job BEFORE it starts: with a synchronous source
        // the collector could finish inside `start()`, and the `finally`
        // block must still find its own job to release `turnActive`.
        turnRuntime.job = job
        job.start()
    }

    /**
     * Starts exactly one stop request for the server-confirmed run of
     * the active turn (Mission 007B). The control is only visible and
     * tappable while [turnActive] and a non-blank [boundRunId] exist, so
     * no stop POST is possible before `run.started`. The in-flight flag
     * is set before the request starts: fast multi-taps and the
     * double-tap window never open a second request.
     */
    fun requestStop() {
        if (!turnActive) return
        if (stopInFlight) return
        val runId = boundRunId?.takeIf { it.isNotBlank() } ?: return
        stopInFlight = true
        stopFailed = false
        val job = coroutineScope.launch {
            val result = try {
                runStopRequester.stopRun(profileId, runId)
            } catch (error: CancellationException) {
                // Propagate: the coroutine is being torn down (back or
                // disposal) while the stop request is in flight. No state
                // is published after cancellation.
                throw error
            } catch (_: Exception) {
                // The repository never throws in production; a throwing
                // fake or unexpected error degrades to the same closed,
                // generic state.
                RunStopResult.Failure(RunStopError.Network)
            }
            // A late result must never mutate a screen that was already
            // left (back) or disposed: the cancellation is observed before
            // any state write (Mission 007B).
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is RunStopResult.Accepted -> {
                    // Accepted only means the server took the stop.
                    // Nothing is set optimistically: the composer stays
                    // blocked and the stream runs on until its own
                    // terminal event settles the turn.
                    Unit
                }
                is RunStopResult.AlreadyTerminal -> {
                    // The run is already terminal: idempotent success.
                    // Trigger the existing reconciliation path — reset
                    // the ephemeral turn state and re-run the
                    // idempotent history read — and cancel the now
                    // stale stream collection. The chat POST is never
                    // resent.
                    stopInFlight = false
                    stopFailed = false
                    boundRunId = null
                    turnFailure = null
                    runStarted = false
                    localLines = emptyList()
                    // The run-specific steer state of the finished run
                    // is stale too; the recoverable text is user input
                    // and is kept (Mission 008B). Any steer draft that
                    // still holds user text is appended as its own
                    // separate recoverable item instead of being
                    // discarded or merged. The draft is cleared BEFORE the
                    // collector below is canceled, so the collector's own
                    // `finally` sees a blank draft and appends nothing —
                    // no double item.
                    val leftoverSteerDraft = steerDraft.takeIf { it.isNotBlank() }
                    steerInFlight = false
                    steerQueued = false
                    steerFailed = false
                    steerDraft = ""
                    // The run is already terminal; no request is in flight
                    // any more. Clear the submitted-text tracking here —
                    // the collector's `finally` skips it on a CANCELLATION
                    // (which this reconciliation triggers), so this explicit
                    // reset is the only one that applies (Mission 008B).
                    steerSubmittedText = null
                    leftoverSteerDraft?.let { appendSteerRecovery(it) }
                    turnRuntime.job?.cancel()
                    loadHistory()
                }
                is RunStopResult.Failure -> {
                    // The running stream collection is left untouched.
                    // One deliberate manual retry is allowed: the
                    // control re-enables with the generic message.
                    stopInFlight = false
                    stopFailed = true
                }
            }
        }
        // The job is registered BEFORE it can finish, so a
        // synchronous fake result still reaches this slot and the
        // disposal / terminal `finally` can cancel it.
        turnRuntime.stopJob = job
    }

    /**
     * Starts exactly one steer request for the server-confirmed run of
     * the active turn (Mission 008B). The control is only visible and
     * tappable while [turnActive] and a non-blank [boundRunId] exist, so
     * no steer POST is possible before `run.started`. The in-flight flag
     * is set before the request starts: fast multi-taps and the
     * double-tap window never open a second request.
     */
    fun requestSteer() {
        if (!turnActive) return
        if (steerInFlight) return
        val runId = boundRunId?.takeIf { it.isNotBlank() } ?: return
        val text = ChatMessageValidator.validate(steerDraft).getOrNull() ?: return
        steerInFlight = true
        steerQueued = false
        steerFailed = false
        // Record the EXACT validated/trimmed text actually sent to the
        // server (Mission 008B), not the mutable/raw [steerDraft]. The
        // terminal `finally` compares THIS against the terminal
        // `pending_steer` to suppress the same-request duplicate — the raw
        // draft would not match a server-normalized value (e.g. input
        // `"  Gleich  "` -> wire `"Gleich"`).
        steerSubmittedText = text
        val job = coroutineScope.launch {
            val result = try {
                runSteerRequester.steerRun(profileId, runId, text)
            } catch (error: CancellationException) {
                // Propagate: the coroutine is being torn down (back or
                // disposal) while the steer request is in flight. No state
                // is published after cancellation.
                throw error
            } catch (_: Exception) {
                // The repository never throws in production; a throwing
                // fake or unexpected error degrades to the same closed,
                // generic state.
                RunSteerResult.Failure(RunSteerError.Network)
            }
            // The request is no longer in flight (queued / 409 / failure),
            // so the submitted-text tracking is cleared on EVERY result —
            // a later same-text steer is a genuinely new request.
            steerSubmittedText = null
            // A late result must never mutate a screen that was already
            // left (back) or disposed: the cancellation is observed before
            // any state write (Mission 008B).
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is RunSteerResult.Queued -> {
                    // Queued only means the server queued the steer: the
                    // run stays active, nothing terminal is set locally.
                    // Show the concise acknowledgement and clear the
                    // steer input (the text now lives on the server).
                    steerInFlight = false
                    steerQueued = true
                    steerDraft = ""
                }
                is RunSteerResult.NotAccepting -> {
                    // The server did not queue the steer (409). Preserve
                    // the exact text in the recoverable state — as its own
                    // separate item, never overwriting an existing item —
                    // and offer the explicit "send as new message" action.
                    // It is never sent automatically (Mission 008B).
                    steerInFlight = false
                    steerQueued = false
                    steerFailed = false
                    appendSteerRecovery(text)
                    steerDraft = ""
                }
                is RunSteerResult.Failure -> {
                    // Closed, generic failure: keep the text in the steer
                    // input for one deliberate manual retry, show the
                    // generic message, never a raw server detail.
                    steerInFlight = false
                    steerQueued = false
                    steerFailed = true
                }
            }
        }
        // The job is registered BEFORE it can finish, so a
        // synchronous fake result still reaches this slot and the
        // disposal / terminal `finally` can cancel it.
        turnRuntime.steerJob = job
    }

    /**
     * Moves exactly ONE recoverable steer — the item at position [index]
     * (a `NotAccepting` steer or a `pending_steer`) — into the ordinary
     * new-message composer (Mission 008B). This is the one explicit
     * action that makes a single item sendable; it never sends it
     * automatically — the user must still press the ordinary "send"
     * action for a deliberate new message.
     *
     * The [index] is the displayed item's own position, so every
     * [SteerRecoveryBlock] action operates on exactly ITS item — never on
     * `first()` regardless of which block was tapped. It is a no-op — and
     * leaves the composer and all recoverable items untouched — while the
     * ordinary composer already holds a nonblank draft (or when [index] is
     * out of range), so it can never silently overwrite the user's
     * current message. The block action is also disabled in that state.
     * Moving one item re-indexes the remaining blocks; the caller always
     * passes the current index of the tapped block.
     */
    fun sendRecoveryAsNewMessage(index: Int) {
        if (draft.isNotBlank()) return
        val item = steerRecovery.getOrNull(index) ?: return
        updateDraft(item)
        steerRecovery = steerRecovery.filterIndexed { i, _ -> i != index }
    }

    // A profile/session switch (or leaving composition) cancels the
    // running collector AND a running stop request. No compose state
    // writes happen in the disposal callback: the keyed `remember` slots
    // above reset every ephemeral state on a switch, the collector's
    // `finally` clears its own bookkeeping on the main scope, and the
    // canceled stop coroutine observes its cancellation before any
    // state write (Mission 007B).
    DisposableEffect(profileId, sessionId) {
        onDispose {
            turnRuntime.job?.cancel()
            turnRuntime.stopJob?.cancel()
            turnRuntime.steerJob?.cancel()
            // Deterministic flush (Mission 009A): persist the pending
            // ordinary draft for the key being left, so a profile/session
            // switch or leaving the screen never loses an in-progress draft
            // and a delayed old-key write can never cross into the new key.
            // (A back that leaves a RUNNING turn has already cleared the
            // draft above, so there is nothing pending to flush.)
            chatDraftRepository.flushPendingDraft()
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
                // double taps are ignored while that is in flight. A
                // running stop request is canceled and joined too, so
                // no late callback can mutate the screen after it is
                // left (Mission 007B); the same holds for a running
                // steer request (Mission 008B).
                backPending = true
                // Capture the in-flight stop job NOW: joining the collector
                // below runs its `finally`, which nulls `turnRuntime.stopJob`
                // in the cancelled branch without canceling it — the
                // reference must be held here or the stop coroutine would
                // survive the back (Mission 007B). The same capture is
                // needed for the steer job (Mission 008B).
                val pendingStop = turnRuntime.stopJob
                val pendingSteer = turnRuntime.steerJob
                coroutineScope.launch {
                    turnRuntime.job?.cancelAndJoin()
                    pendingStop?.cancelAndJoin()
                    pendingSteer?.cancelAndJoin()
                    turnActive = false
                    runStarted = false
                    boundRunId = null
                    stopInFlight = false
                    stopFailed = false
                    localLines = emptyList()
                    turnFailure = null
                    // Back leaves the screen: clear the composer AND its
                    // stored state so the in-turn draft is not re-persisted
                    // as a draft for the left session (Mission 009A).
                    clearDraftState()
                    // The run-specific steer state (incl. the recoverable
                    // items) belongs to the left run; the screen leaves
                    // composition, so the keyed slots reset anyway.
                    steerInFlight = false
                    steerQueued = false
                    steerFailed = false
                    steerDraft = ""
                    // Session boundary: the in-flight submitted-text
                    // tracking of the left session is cleared too
                    // (Mission 008B).
                    steerSubmittedText = null
                    steerRecovery = emptyList()
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
                            // it, once, on the main scope. The run stop
                            // state of the finished turn is cleared too
                            // (Mission 007B), as is the run-specific
                            // steer state (Mission 008B).
                            localLines = emptyList()
                            turnFailure = null
                            runStarted = false
                            boundRunId = null
                            pendingApproval = null
                            stopInFlight = false
                            steerInFlight = false
                            steerQueued = false
                            steerFailed = false
                            steerDraft = ""
                            // Refresh after a failed turn: no request is in
                            // flight for the finished run, so the submitted-
                            // text tracking is cleared at this boundary too
                            // (Mission 008B).
                            steerSubmittedText = null
                            loadHistory()
                        }
                    )
                }
                // Stop control (Mission 007B): visible only for an
                // active turn whose server-confirmed, non-blank run ID
                // is bound from `RunStarted` — before `run.started` the
                // control is invisible and no stop POST is possible.
                // While a stop request is in flight it is disabled and
                // shows the "stopping" label. After a closed, generic
                // failure it re-enables for one deliberate manual retry.
                if (turnActive && boundRunId?.isNotBlank() == true) {
                    StopControl(
                        label = if (stopInFlight) stoppingLabel else stopLabel,
                        enabled = !stopInFlight,
                        showStopError = stopFailed && !stopInFlight,
                        stopErrorLabel = stopErrorLabel,
                        onStop = { requestStop() },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    // Steer control (Mission 008B): the same visibility
                    // contract as the stop control — only for an active
                    // turn whose server-confirmed, non-blank run ID is
                    // bound. Distinct input and action; it never
                    // overloads the ordinary new-message composer.
                    SteerControl(
                        draft = steerDraft,
                        onDraftChange = { steerDraft = it },
                        inFlight = steerInFlight,
                        queued = steerQueued,
                        failed = steerFailed,
                        steerLabel = steerLabel,
                        inputLabel = steerInputLabel,
                        queuedLabel = steerQueuedLabel,
                        errorLabel = steerErrorLabel,
                        onSteer = { requestSteer() },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Recoverable steers (Mission 008B): each preserved item —
                // a `NotAccepting` steer or a `pending_steer` from
                // `run.completed` — is rendered as its own ordered block.
                // Visible also after the terminal event (that is exactly
                // the `pending_steer` case). Each item's single action
                // moves exactly that one item into the ordinary composer —
                // it never sends it, never merges it, and is disabled (and
                // a no-op) while the ordinary composer already holds a
                // nonblank draft.
                if (steerRecovery.isNotEmpty()) {
                    steerRecovery.forEachIndexed { index, text ->
                        // Each block's action targets EXACTLY this item's
                        // position, so tapping block N moves item N —
                        // never the first item (Mission 008B).
                        SteerRecoveryBlock(
                            note = "$steerRecoveryLabel ${index + 1}/${steerRecovery.size}",
                            text = text,
                            actionLabel = steerSendAsNewMessageLabel,
                            enabled = draft.isBlank(),
                            onSendAsNewMessage = { sendRecoveryAsNewMessage(index) },
                            testTag = "chatSteerRecovery:$index",
                            actionTestTag = "chatSteerSendAsNewMessage:$index",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                ComposerBar(
                    draft = draft,
                    onDraftChange = { if (!turnActive) updateDraft(it) },
                    sendEnabled = !turnActive &&
                        (turnFailure == null || !runStarted) &&
                        ChatMessageValidator.validate(draft).isSuccess,
                    onSend = { submit() },
                    turnActive = turnActive
                )
            }
        }
    }
    // Run-approval slice (Mission 014): the capability gate + dialog hang in
    // the composition tree as a window overlay. The gate is [approvalUiEnabled]
    // (the fail-closed, onboarding-confirmed `features.run_approval_response`
    // capability) AND a non-null [ApprovalRequest]. There is currently NO live
    // source for an `approval.request` (it is only ever sent on the run
    // event stream the app does not consume), so [pendingApproval] is passed
    // as null: the dialog is therefore invisible today, and NOTHING can be
    // rendered without both a confirmed capability and a pending request.
    // No network or SSE wiring is introduced here.
    ApprovalUi(
        approvalUiEnabled = approvalUiEnabled,
        pendingApproval = pendingApproval,
        profileId = profileId,
        requester = runApprovalRequester,
        onDismiss = { pendingApproval = null }
    )
}

/**
 * Holder for the running turn collector and the in-flight stop and steer
 * requests. It is deliberately not keyed by profile/session (see
 * [ChatScreen]) so a disposed effect can still cancel the collector and
 * both requests of the previous session.
 */
private class TurnRuntime {
    var job: Job? = null
    var stopJob: Job? = null
    var steerJob: Job? = null
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

/**
 * The run-stop control (Mission 007B). [label] is the "Stop" label or,
 * while a stop request is in flight, the "stopping" label; [enabled] is
 * false while the request is in flight or while no server-confirmed run
 * ID is bound yet. [showStopError] renders the one closed, generic stop
 * failure message (no dynamic server detail, no run ID).
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
 * The run-steer control (Mission 008B). A distinct input plus its own
 * "steer" action, only rendered for an active turn with a bound,
 * non-blank run ID. [inFlight] disables input and action (fast
 * multi-taps never open a second request); [queued] renders the concise
 * acknowledgement after a `Queued` answer; [failed] renders the one
 * closed, generic failure message (no dynamic server detail) and keeps
 * the text in [draft] for a deliberate manual retry.
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
 * One ordered block for a single recoverable steer (Mission 008B): a
 * `NotAccepting` steer or a `pending_steer`. [note] is the generic
 * recoverable note with an `index/total` counter; [text] is the exact
 * preserved item text. The single [actionLabel] action moves exactly
 * THIS one item (its own position, wired by the caller) into the
 * ordinary composer; it never sends it and is disabled — and a no-op in
 * [ChatScreen.sendRecoveryAsNewMessage] — while [enabled] is false (i.e.
 * the ordinary composer is nonblank), so it can never overwrite the
 * user's in-progress message. [testTag] tags the whole block;
 * [actionTestTag] tags the action button uniquely per item (the plain
 * text label is shared by every block and therefore ambiguous in
 * Compose selectors).
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
