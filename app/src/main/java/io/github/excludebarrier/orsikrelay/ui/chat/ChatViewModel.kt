package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

/**
 * View model owning the chat screen's turn state machine (P1 refactor).
 *
 * The screen composable no longer holds any business state: it renders
 * [state] and forwards user intents to the methods below. Every transition
 * of the verified P0 state machine (history load, draft, send/streaming,
 * run stop, run steer, recoverable steers, back, approval) is reproduced
 * here one-to-one. The only structural change is that the screen-local
 * `mutableStateOf` slots are replaced by a single immutable [ChatUiState]
 * published through [state], and the composition-scoped coroutines become
 * children of this view model's own [scope].
 *
 * The view model is intentionally a plain class (not an
 * `androidx.lifecycle.ViewModel`): the screen is keyed by
 * `(profileId, sessionId)` through `remember`, so a key switch recreates the
 * view model and its state resets exactly as the previous keyed slots did.
 * The ordinary draft survives a key switch / disposal through the
 * [ChatDraftRepository] (the debounced value is flushed on [close]).
 *
 * Network-protocol logic is NOT here: [chatSource] yields already-validated
 * [ChatTurnEvent]s; the SSE state machine and the strict protocol-v1 wire
 * contract stay in the repository/pipeline layer below.
 */
internal class ChatViewModel(
    private val historySource: MessageHistorySource,
    private val chatSource: StreamingChatSource,
    private val runStopRequester: RunStopRequester,
    private val runSteerRequester: RunSteerRequester,
    private val chatDraftRepository: ChatDraftRepository,
    private val profileId: String,
    private val sessionId: String,
    private val onBack: () -> Unit,
    private val toolActivityLabel: String,
    private val namedToolActivityLabel: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    // Bookkeeping that is not UI-observable and therefore kept OUT of
    // [ChatUiState]: the per-screen local-row counter and the saveable-style
    // draft generation guard.
    private var localKeyCounter = 0
    private var draftGeneration = 0

    // Job handles for the active turn collector and the in-flight stop and
    // steer requests. Deliberately not part of the immutable state: they
    // exist so a back can join them before navigating (Mission 007B/008B) and
    // so a key switch / disposal can cancel the previous session's collectors.
    private var collectorJob: Job? = null
    private var stopJob: Job? = null
    private var steerJob: Job? = null

    private fun nextLocalKey(): String {
        localKeyCounter += 1
        return "local:$localKeyCounter"
    }

    /**
     * Appends one recoverable steer (Mission 008B) as its own ordered item.
     * Items are never merged: two occurrences with identical text remain two
     * separate items, each kept intact.
     */
    private fun appendSteerRecovery(text: String) {
        _state.update { it.copy(steerRecovery = it.steerRecovery + text) }
    }

    /**
     * Loads the message history for the active key (Mission 006A). Guarded so
     * a retry/refresh tap can never start a second load while one is in
     * flight; the guard is released on the main scope BEFORE the finished
     * Ready/Failed state is published, so the screen never shows a tappable
     * retry/refresh while the guard still swallows the next load.
     */
    fun loadHistory() {
        if (_state.value.loadInFlight) return
        _state.update {
            it.copy(
                loadInFlight = true,
                history = MessageHistoryState.Loading,
                turnFailure = null
            )
        }
        scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    historySource.loadHistory(profileId, sessionId)
                }
            } catch (error: CancellationException) {
                // Rethrown after the `finally` below released the guard;
                // no state is published after cancellation.
                throw error
            } catch (_: Exception) {
                MessageHistoryResult.Failure(MessageHistoryError.Network)
            } finally {
                _state.update { it.copy(loadInFlight = false) }
            }
            _state.update {
                it.copy(
                    history = when (result) {
                        is MessageHistoryResult.Success ->
                            MessageHistoryState.Ready(result.page.messages)
                        is MessageHistoryResult.Failure ->
                            MessageHistoryState.Failed(result.error)
                    }
                )
            }
        }
    }

    /**
     * Restores the persisted ordinary draft for the active key (Mission
     * 009A). Zero is the only generation with no newer local intent: any
     * local draft mutation bumps [draftGeneration], so a delayed stored value
     * must never overwrite newer local intent.
     */
    suspend fun restoreDraft() {
        val loadedKey = profileId to sessionId
        val generationAtLoadStart = draftGeneration
        val restored = chatDraftRepository.loadDraft(profileId, sessionId)
        if (
            restored != null &&
            loadedKey == (profileId to sessionId) &&
            generationAtLoadStart == 0 &&
            generationAtLoadStart == draftGeneration
        ) {
            _state.update { it.copy(draft = restored) }
            draftGeneration += 1
        }
    }

    /**
     * Records one ordinary-draft change from the composer and schedules its
     * debounced, latest-value persist for the active key (Mission 009A).
     * Ignored while a turn is active (the composer is disabled then).
     */
    fun onDraftChange(value: String) {
        if (_state.value.turnActive) return
        _state.update { it.copy(draft = value) }
        draftGeneration += 1
        chatDraftRepository.persistDraft(profileId, sessionId, value)
    }

    /**
     * Records one change of the run-steer input (Mission 008B). The steer
     * draft is separate from the ordinary composer draft and is never
     * persisted: it is only visible for an active, run-bound turn.
     */
    fun onSteerDraftChange(value: String) {
        _state.update { it.copy(steerDraft = value) }
    }

    /**
     * Moves exactly ONE recoverable steer — the item at position [index] —
     * into the ordinary new-message composer (Mission 008B). It never sends
     * it automatically and is a no-op while the ordinary composer already
     * holds a nonblank draft, so it can never silently overwrite the user's
     * current message.
     */
    fun sendRecoveryAsNewMessage(index: Int) {
        val current = _state.value
        if (current.draft.isNotBlank()) return
        val item = current.steerRecovery.getOrNull(index) ?: return
        _state.update { it.copy(draft = item) }
        draftGeneration += 1
        chatDraftRepository.persistDraft(profileId, sessionId, item)
        _state.update { it.copy(steerRecovery = it.steerRecovery.filterIndexed { i, _ -> i != index }) }
    }

    /**
     * Clears the ordinary draft and its stored state together (Mission
     * 009A). Used exactly where the composer is cleared on a successful send
     * (`RunStarted`) and on a back that leaves a running turn, so the
     * just-sent text is never re-persisted as a draft.
     */
    private fun clearDraftState() {
        _state.update { it.copy(draft = "") }
        draftGeneration += 1
        chatDraftRepository.clearDraft(profileId, sessionId)
    }

    /**
     * The "refresh history" action after a failed turn (Mission 006A/007B/
     * 008B): resets the ephemeral local lines, the turn failure and the
     * start flag, and the run-specific stop/steer state of the finished turn,
     * then re-runs the idempotent history read. The POST is never resent.
     */
    fun refreshHistory() {
        _state.update {
            it.copy(
                localLines = emptyList(),
                turnFailure = null,
                runStarted = false,
                boundRunId = null,
                pendingApproval = null,
                stopInFlight = false,
                steerInFlight = false,
                steerQueued = false,
                steerFailed = false,
                steerDraft = "",
                steerSubmittedText = null
            )
        }
        loadHistory()
    }

    /**
     * Dismisses the pending approval dialog (Mission 014). Invoked by the
     * dialog only after a `Resolved` decision; the decision POST itself is
     * owned by the dialog's own composition scope, not by this view model.
     */
    fun dismissApproval() {
        _state.update { it.copy(pendingApproval = null) }
    }

    /**
     * Starts exactly one streaming turn for the validated composer draft
     * (Mission 006A). The local user line appears immediately after the
     * accepted submit; the draft is cleared only on `RunStarted`. A failure
     * before the confirmed start keeps the draft and drops the premature
     * user line. The collector is registered in [collectorJob] BEFORE it
     * starts so a synchronous source still reaches the slot and the terminal
     * `finally` can release `turnActive`.
     */
    fun send() {
        val current = _state.value
        if (current.turnActive) return
        if (current.history !is MessageHistoryState.Ready) return
        // A failure before the confirmed start does not block a new,
        // deliberate submit; only an active turn or a failure after the
        // confirmed start does.
        if (current.turnFailure != null && current.runStarted) return
        val message = ChatMessageValidator.validate(current.draft).getOrNull() ?: return
        val text = current.draft
        _state.update {
            it.copy(
                turnActive = true,
                turnFailure = null,
                runStarted = false,
                // A new turn starts with a fresh stop state: the previous run
                // ID and any in-flight stop belong to the finished turn and
                // must not leak into this one (Mission 007B).
                boundRunId = null,
                stopInFlight = false,
                stopFailed = false,
                // A new turn also starts with a fresh steer state (Mission
                // 008B). The recoverable text is deliberately KEPT.
                steerInFlight = false,
                steerQueued = false,
                steerFailed = false,
                steerDraft = "",
                steerSubmittedText = null
            )
        }
        // The local user line appears immediately after the accepted submit;
        // the draft is cleared only on `RunStarted`.
        val userLineKey = nextLocalKey()
        _state.update {
            it.copy(
                localLines = it.localLines + TimelineRow(
                    key = userLineKey,
                    role = MessageRole.USER,
                    content = message
                )
            )
        }

        fun dropPrematureUserLine() {
            if (!_state.value.runStarted) {
                // Failure before confirmed `run.started`: drop the premature
                // local user line and keep the draft in the composer.
                _state.update { s ->
                    s.copy(localLines = s.localLines.filter { it.key != userLineKey })
                }
            }
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
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
                            // The server-confirmed run ID is the only value the
                            // stop control may use; it is bound here and never
                            // rendered or logged. Successful send handoff:
                            // clear the composer AND its stored state together
                            // (Mission 009A).
                            _state.update { s -> s.copy(runStarted = true, boundRunId = event.runId) }
                            clearDraftState()
                        }
                        is ChatTurnEvent.AssistantStarted -> {
                            // Do not render an empty bubble: a tool may run
                            // before the first assistant token.
                        }
                        is ChatTurnEvent.AssistantDelta -> {
                            _state.update { s ->
                                val last = s.localLines.lastOrNull()
                                s.copy(
                                    localLines = if (last?.role == MessageRole.ASSISTANT) {
                                        appendToLastAssistantLine(s.localLines, event.text)
                                    } else {
                                        s.localLines + TimelineRow(
                                            key = nextLocalKey(),
                                            role = MessageRole.ASSISTANT,
                                            content = event.text
                                        )
                                    }
                                )
                            }
                        }
                        is ChatTurnEvent.AssistantCompleted -> {
                            _state.update { s ->
                                val last = s.localLines.lastOrNull()
                                s.copy(
                                    localLines = if (last?.role == MessageRole.ASSISTANT) {
                                        replaceLastAssistantLine(s.localLines, event.finalText)
                                    } else {
                                        // A tool-only turn: the authoritative
                                        // final answer is appended after its
                                        // final tool row.
                                        s.localLines + TimelineRow(
                                            key = nextLocalKey(),
                                            role = MessageRole.ASSISTANT,
                                            content = event.finalText
                                        )
                                    }
                                )
                            }
                        }
                        is ChatTurnEvent.ApprovalRequested -> {
                            // A second request while one is still pending is
                            // contradictory and must never overwrite the
                            // actionable request.
                            if (_state.value.pendingApproval != null) {
                                throw ConcurrentApprovalException()
                            }
                            _state.update { s -> s.copy(pendingApproval = event.request) }
                        }
                        is ChatTurnEvent.ToolActivity -> {
                            // Generic status only: never a tool name or payload
                            // (those exist only for server history lines).
                            _state.update { s ->
                                s.copy(
                                    localLines = s.localLines + TimelineRow(
                                        key = nextLocalKey(),
                                        role = MessageRole.TOOL,
                                        content = event.toolName?.let {
                                            namedToolActivityLabel.format(it)
                                        } ?: toolActivityLabel,
                                        localTool = true
                                    )
                                )
                            }
                        }
                        is ChatTurnEvent.TurnCompleted -> {
                            completed = true
                            _state.update { s -> s.copy(pendingApproval = null) }
                            val pending = event.pendingSteer
                            if (pending != null) {
                                // Mission 008B: a `pending_steer` carried by
                                // `run.completed` is surfaced through the same
                                // recoverable state, as its own separate item.
                                completedPendingSteer = pending
                                appendSteerRecovery(pending)
                            }
                        }
                        is ChatTurnEvent.TurnFailed -> {
                            _state.update {
                                s -> s.copy(pendingApproval = null, turnFailure = event.error)
                            }
                            dropPrematureUserLine()
                        }
                    }
                    event !is ChatTurnEvent.TurnCompleted &&
                        event !is ChatTurnEvent.TurnFailed
                }.collect { }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (_: ConcurrentApprovalException) {
                _state.update {
                    it.copy(pendingApproval = null, turnFailure = ChatStreamError.IncompatibleApi)
                }
                dropPrematureUserLine()
            } catch (_: Exception) {
                _state.update { it.copy(turnFailure = ChatStreamError.Network) }
                dropPrematureUserLine()
            } finally {
                // A flow that ends without `TurnCompleted` or `TurnFailed` must
                // not silently release the composer: a closed stream is a
                // generic network failure, and there is never an automatic
                // retry.
                if (!cancelled && !completed && _state.value.turnFailure == null) {
                    _state.update { it.copy(turnFailure = ChatStreamError.Network) }
                    dropPrematureUserLine()
                }
                // Bookkeeping is cleared here, on the main scope — never from a
                // foreign `invokeOnCompletion` thread.
                if (collectorJob === self) {
                    collectorJob = null
                    _state.update {
                        it.copy(turnActive = false, pendingApproval = null, backPending = false)
                    }
                    if (!cancelled) {
                        // The turn settled: a stop request that is still in
                        // flight belongs to the finished run and is canceled so
                        // no late callback can mutate the screen state
                        // (Mission 007B). The same holds for a steer request
                        // (Mission 008B).
                        stopJob?.cancel()
                        steerJob?.cancel()
                    }
                    stopJob = null
                    steerJob = null
                    // Capture the in-flight request's EXACT submitted text and
                    // the leftover draft BEFORE clearing the flags (Mission
                    // 008B): they are only known at this moment.
                    val inFlightSteerText =
                        if (_state.value.steerInFlight) _state.value.steerSubmittedText else null
                    val leftoverSteerDraft = _state.value.steerDraft.takeIf { it.isNotBlank() }
                    _state.update {
                        it.copy(
                            stopInFlight = false,
                            stopFailed = false,
                            boundRunId = null,
                            steerInFlight = false,
                            steerQueued = false,
                            steerFailed = false,
                            steerDraft = "",
                            steerSubmittedText = null
                        )
                    }
                    // Same-request duplicate suppression (Mission 008B): if the
                    // in-flight request was already reported by this turn's
                    // terminal `pending_steer`, it has been appended exactly
                    // once already. The comparison uses the exact
                    // validated/trimmed wire text — never the raw draft.
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
        // Register the job BEFORE it starts: with a synchronous source the
        // collector could finish inside `start()`, and the `finally` block
        // must still find its own job to release `turnActive`.
        collectorJob = job
        job.start()
    }

    /**
     * Starts exactly one stop request for the server-confirmed run of the
     * active turn (Mission 007B). The in-flight flag is set before the request
     * starts: fast multi-taps never open a second request. `Accepted` only
     * marks the request; nothing is set optimistically. `AlreadyTerminal`
     * runs the reconciliation path; `Failure` leaves one deliberate retry.
     */
    fun stop() {
        val current = _state.value
        if (!current.turnActive) return
        if (current.stopInFlight) return
        val runId = current.boundRunId?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(stopInFlight = true, stopFailed = false) }
        val job = scope.launch {
            val result = try {
                runStopRequester.stopRun(profileId, runId)
            } catch (error: CancellationException) {
                // Propagate: the coroutine is being torn down (back or
                // disposal) while the stop request is in flight.
                throw error
            } catch (_: Exception) {
                // The repository never throws in production; a throwing fake
                // degrades to the same closed, generic state.
                RunStopResult.Failure(RunStopError.Network)
            }
            // A late result must never mutate a screen that was already left
            // (back) or disposed (Mission 007B).
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is RunStopResult.Accepted -> {
                    // Accepted only means the server took the stop. Nothing is
                    // set optimistically: the stream runs on until its own
                    // terminal event settles the turn.
                    Unit
                }
                is RunStopResult.AlreadyTerminal -> {
                    // The run is already terminal: idempotent success. Trigger
                    // the existing reconciliation path — reset the ephemeral
                    // turn state and re-run the idempotent history read — and
                    // cancel the now stale stream collection. The chat POST is
                    // never resent.
                    val leftoverSteerDraft = _state.value.steerDraft.takeIf { it.isNotBlank() }
                    _state.update {
                        it.copy(
                            stopInFlight = false,
                            stopFailed = false,
                            boundRunId = null,
                            turnFailure = null,
                            runStarted = false,
                            localLines = emptyList(),
                            steerInFlight = false,
                            steerQueued = false,
                            steerFailed = false,
                            steerDraft = "",
                            steerSubmittedText = null
                        )
                    }
                    leftoverSteerDraft?.let { appendSteerRecovery(it) }
                    collectorJob?.cancel()
                    loadHistory()
                }
                is RunStopResult.Failure -> {
                    // The running stream collection is left untouched. One
                    // deliberate manual retry is allowed.
                    _state.update { it.copy(stopInFlight = false, stopFailed = true) }
                }
            }
        }
        // The job is registered BEFORE it can finish, so a synchronous fake
        // result still reaches this slot and the disposal / terminal `finally`
        // can cancel it.
        stopJob = job
    }

    /**
     * Starts exactly one steer request for the server-confirmed run of the
     * active turn (Mission 008B). The in-flight flag is set before the request
     * starts: fast multi-taps never open a second request. [RunSteerResult]
     * `Queued` acknowledges and clears the input; `NotAccepting` preserves the
     * text as a recoverable item; `Failure` keeps the text for one deliberate
     * retry.
     */
    fun steer() {
        val current = _state.value
        if (!current.turnActive) return
        if (current.steerInFlight) return
        val runId = current.boundRunId?.takeIf { it.isNotBlank() } ?: return
        val text = ChatMessageValidator.validate(current.steerDraft).getOrNull() ?: return
        // Record the EXACT validated/trimmed text actually sent (Mission
        // 008B), not the mutable/raw steer draft.
        _state.update {
            it.copy(
                steerInFlight = true,
                steerQueued = false,
                steerFailed = false,
                steerSubmittedText = text
            )
        }
        val job = scope.launch {
            val result = try {
                runSteerRequester.steerRun(profileId, runId, text)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RunSteerResult.Failure(RunSteerError.Network)
            }
            // The request is no longer in flight (queued / 409 / failure), so
            // the submitted-text tracking is cleared on EVERY result (Mission
            // 008B).
            _state.update { it.copy(steerSubmittedText = null) }
            // A late result must never mutate a screen that was already left
            // (back) or disposed (Mission 008B).
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is RunSteerResult.Queued -> {
                    _state.update {
                        it.copy(steerInFlight = false, steerQueued = true, steerDraft = "")
                    }
                }
                is RunSteerResult.NotAccepting -> {
                    // Preserve the exact text in the recoverable state — as its
                    // own separate item — and clear the steer input.
                    _state.update {
                        it.copy(
                            steerInFlight = false,
                            steerQueued = false,
                            steerFailed = false,
                            steerDraft = ""
                        )
                    }
                    appendSteerRecovery(text)
                }
                is RunSteerResult.Failure -> {
                    // Keep the text in the steer input for one deliberate
                    // manual retry.
                    _state.update {
                        it.copy(steerInFlight = false, steerQueued = false, steerFailed = true)
                    }
                }
            }
        }
        // The job is registered BEFORE it can finish.
        steerJob = job
    }

    /**
     * Routes the platform back (button, system key or gesture) exactly like
     * the visible button (Mission 006A/007B/008B): with no active turn it
     * navigates back immediately; with an active turn it cancels AND joins the
     * collector plus any in-flight stop/steer request, resets the ephemeral
     // state, clears the in-turn draft, and then navigates back exactly once.
     * Fast double taps are ignored while the back is in flight.
     */
    fun handleBack() {
        val current = _state.value
        when {
            !current.turnActive -> onBack()
            current.backPending -> Unit
            else -> {
                _state.update { it.copy(backPending = true) }
                // Capture the in-flight stop/steer jobs NOW: joining the
                // collector runs its `finally`, which nulls them without
                // canceling in the cancelled branch (Mission 007B/008B).
                val pendingStop = stopJob
                val pendingSteer = steerJob
                scope.launch {
                    collectorJob?.cancelAndJoin()
                    pendingStop?.cancelAndJoin()
                    pendingSteer?.cancelAndJoin()
                    _state.update {
                        it.copy(
                            turnActive = false,
                            runStarted = false,
                            boundRunId = null,
                            stopInFlight = false,
                            stopFailed = false,
                            localLines = emptyList(),
                            turnFailure = null
                        )
                    }
                    // Back leaves the screen: clear the composer AND its stored
                    // state so the in-turn draft is not re-persisted as a draft
                    // for the left session (Mission 009A).
                    clearDraftState()
                    // The run-specific steer state (incl. the recoverable items)
                    // belongs to the left run (Mission 008B).
                    _state.update {
                        it.copy(
                            steerInFlight = false,
                            steerQueued = false,
                            steerFailed = false,
                            steerDraft = "",
                            steerSubmittedText = null,
                            steerRecovery = emptyList()
                        )
                    }
                    onBack()
                }
            }
        }
    }

    /**
     * Tears the view model down on a key switch or leaving composition
     * (Mission 007B/009A): cancels the running collector AND any in-flight
     * stop/steer request of the previous session, and deterministically
     * flushes the pending ordinary draft so it is neither lost nor able to
     * land under the new key. No state is published after cancellation.
     */
    fun close() {
        scope.cancel()
        chatDraftRepository.flushPendingDraft()
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
}

/**
 * Raised when a second `approval.request` arrives for the active turn while
 * one is still pending (Mission 014). It is a protocol contradiction: the
 * actionable request must never be overwritten, so the turn fails with a
 * closed `IncompatibleApi` code instead.
 */
private class ConcurrentApprovalException : Exception()
