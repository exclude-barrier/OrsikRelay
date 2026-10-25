package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentRules
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryPaging
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationPoster
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController.StartTurnResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunUnknownResolver
import io.github.excludebarrier.orsikrelay.domain.run.RunUnknownResolver
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * View model owning the chat screen's turn state machine.
 *
 * The screen composable no longer holds any business state: it renders
 * [state] and forwards user intents to the methods below. Every transition
 * of the verified baseline state machine (history load, draft, send/streaming,
 * run stop, run steer, recoverable steers, back, approval) is reproduced
 * here one-to-one. The screen-local `mutableStateOf` slots are replaced by
 * a single immutable [ChatUiState] published through [state], and the
 * composition-scoped coroutines become children of this view model's own
 * [scope].
 *
 * The view model is intentionally a plain class (not an
 * `androidx.lifecycle.ViewModel`): the screen is keyed by
 * `(profileId, sessionId)` through `remember`, so a key switch recreates the
 * view model and its state resets exactly as the previous keyed slots did.
 * The ordinary draft survives a key switch / disposal through the
 * [ChatDraftRepository] (the debounced value is flushed on [close]).
 *
 * Network-protocol logic is NOT here: [runController] (app-level) owns
 * the active run's STREAM and foreground-service lifecycle, so a run
 * survives leaving the chat screen, an activity recreation and the app
 * going to the background. This view model only observes the controller's
 * [RunSessionController.events] and renders them; leaving the screen no
 * longer interrupts the run. If the view model is created while the
 * session's run is already active (re-entry, deep-link tap, recreation) it
 * re-attaches to that run's state and rebuilds the visible turn (user line,
 * composed assistant text, reconnecting/approval status) from the
 * controller's durable in-memory turn state - so an Activity recreation
 * neither loses the turn nor starts a second POST.
 */
internal class ChatViewModel(
    private val historySource: MessageHistorySource,
    private val runController: RunSessionController,
    private val runStopRequester: RunStopRequester,
    private val runSteerRequester: RunSteerRequester,
    /** Reads the server-side status of a confirmed uncertain run (manual recovery). */
    private val runStatusRequester: RunStatusRequester = NoOpRunStatusRequester,
    /** Closes a run the core lost, on the user's explicit tap only. */
    private val runUnknownResolver: RunUnknownResolver = NoOpRunUnknownResolver,
    private val chatDraftRepository: ChatDraftRepository,
    /** Reads the picked attachment's metadata/bytes; no-op when unwired. */
    private val attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
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
    // [ChatUiState]: the per-screen local-row counter, the saveable-style
    // draft generation guard, the key of the premature local user line of
    // the active turn (dropped on a pre-`RunStarted` failure) and the
    // `pending_steer` carried by the active turn's terminal event.
    private var localKeyCounter = 0
    private var draftGeneration = 0
    private var activeUserLineKey: String? = null
    // Key of the user line REHYDRATED from the controller after a
    // recreation. It is dropped as soon as the loaded server history carries
    // the same message (the server stores it when the run starts), so a
    // re-created screen never renders the user's message twice.
    private var rehydratedUserLineKey: String? = null
    private var terminalPendingSteer: String? = null
    // True only once THIS screen has actually handed a turn to the
    // controller ([RunSessionController.startTurn] was called / a matching
    // active run was rehydrated). It gates [handleRunEvent]: while an
    // attachment is still being read the screen shows an optimistic turn
    // (turnActive) but does NOT own the stream yet, so events of a foreign
    // run that wins the global slot in the meantime can never be misread as
    // this screen's own turn.
    private var ownsRun = false

    // Job handles for the in-flight stop and steer requests. Deliberately
    // not part of the immutable state: they exist so a settled turn can
    // cancel a request that belongs to the finished run
    // and disposal can cancel a request of the left screen.
    private var stopJob: Job? = null
    private var steerJob: Job? = null
    private var olderLoadJob: Job? = null

    /**
     * Raw server rows consumed by the visible history, NOT the number of
     * visible rows. This is the `offset` for the next older page and is
     * advanced ONLY by a successful history page's `pagination.returned`.
     * Local/optimistic/streamed turn lines and hidden server rows never
     * touch it, so an active turn cannot falsify the offset.
     */
    private var loadedServerMessages = 0

    /**
     * Incremented whenever the main history is (re)loaded. An older-page
     * result that belongs to an older generation is discarded. A session
     * switch replaces this view model entirely (the screen is keyed by
     * profile+session), and [close] cancels the scope, so no result can
     * cross into another session either.
     */
    private var historyGeneration = 0

    init {
        // Re-attach: the run (if any) is owned app-level by the controller,
        // so a view model created mid-run — re-entry, deep-link tap on the
        // run/approval notification, activity recreation — restores the
        // run UI state instead of starting from a blank screen.
        val active = runController.activeRun.value
        if (active != null && active.profileId == profileId && active.sessionId == sessionId) {
            // Rebuild the visible turn from the controller's durable,
            // in-memory turn state: the user line and
            // the assistant text received so far, plus the run identity and
            // the transient statuses. Deliberately rebuilt ONLY from the
            // closed, UI-safe fields - no command, argument, path, raw tool
            // output or credential ever enters this state.
            val rows = mutableListOf<TimelineRow>()
            var rehydratedUserKey: String? = null
            if (active.userMessage.isNotBlank()) {
                rehydratedUserKey = nextLocalKey()
                rehydratedUserLineKey = rehydratedUserKey
                rows += TimelineRow(
                    key = rehydratedUserKey,
                    role = MessageRole.USER,
                    content = active.userMessage
                )
            }
            // Tool activity rows are rebuilt BEFORE the assistant row: the
            // assistant row must stay last so a following delta appends to it
            // (and `assistant.completed` replaces it) instead of creating a
            // second assistant row. The names come from the already redacted
            // domain event, never from a raw payload.
            for (toolName in active.toolNames) {
                rows += TimelineRow(
                    key = nextLocalKey(),
                    role = MessageRole.TOOL,
                    content = namedToolActivityLabel.format(toolName),
                    toolName = toolName,
                    localTool = true
                )
            }
            if (active.assistantText.isNotEmpty()) {
                rows += TimelineRow(
                    key = nextLocalKey(),
                    role = MessageRole.ASSISTANT,
                    content = active.assistantText
                )
            }
            // Only an UNCONFIRMED run still owns a premature user line that a
            // pre-`RunStarted` failure may drop; a confirmed run keeps its row.
            activeUserLineKey = if (active.runId == null) rehydratedUserKey else null
            _state.update {
                it.copy(
                    turnActive = true,
                    // The flag means "the server confirmed this run": a
                    // re-created screen must not claim a confirmed run that
                    // the server has not acknowledged yet.
                    runStarted = active.runId != null,
                    boundRunId = active.runId,
                    pendingApproval = active.pendingApproval,
                    reconnecting = active.reconnecting,
                    turnUncertain = active.uncertain,
                    localLines = rows
                )
            }
            // A rehydrated matching run is owned by this screen.
            ownsRun = true
        }
        // Attachment rehydration: a recreation re-creates this view model, and the
        // picker's read permission lives as long as the process, so the
        // picked-but-unsent attachment is re-read from the process-scoped
        // holder (exactly this profile+session).
        ChatAttachmentDraftHolder.get(profileId, sessionId)?.let { draft ->
            _state.update { it.copy(attachment = draft) }
        }
        // Long-lived event observer: the stream itself lives on the
        // controller's app scope; this collector only renders events into
        // [state] and dies with the screen (disposal), never with the run.
        scope.launch {
            runController.events.collect { event ->
                handleRunEvent(event)
            }
        }
        // The conflict notice is transient. It clears as soon as the
        // app-wide run slot is free again (the owning run reached a real
        // terminal state or was explicitly stopped). The waiting draft is
        // deliberately NOT sent automatically - the user decides.
        scope.launch {
            runController.activeRun.collect { active ->
                if (active == null) {
                    _state.update { current ->
                        if (current.startConflict) current.copy(startConflict = false) else current
                    }
                } else if (
                    // The run lease of a dead process is restored
                    // asynchronously. A screen created before that restore
                    // finished adopts the restored UNCERTAIN run of its own
                    // session, so the manual reconciliation is offered and
                    // the composer stays blocked (no second POST).
                    // An uncertain run without a confirmed ID (the send
                    // failed before the server confirmed it) is adopted too,
                    // so this chat can offer the release in the app.
                    active.uncertain &&
                    active.profileId == profileId &&
                    active.sessionId == sessionId &&
                    !_state.value.turnActive
                ) {
                    _state.update {
                        it.copy(
                            turnActive = true,
                            runStarted = active.runId != null,
                            boundRunId = active.runId,
                            turnUncertain = true,
                            startConflict = false
                        )
                    }
                    ownsRun = true
                }
            }
        }
    }

    private fun nextLocalKey(): String {
        localKeyCounter += 1
        return "local:$localKeyCounter"
    }

    /**
     * Appends one recoverable steer as its own ordered item.
     * Items are never merged: two occurrences with identical text remain two
     * separate items, each kept intact.
     */
    private fun appendSteerRecovery(text: String) {
        _state.update { it.copy(steerRecovery = it.steerRecovery + text) }
    }

    /**
     * Loads the message history for the active key. Guarded so
     * a retry/refresh tap can never start a second load while one is in
     * flight; the guard is released on the main scope BEFORE the finished
     * Ready/Failed state is published, so the screen never shows a tappable
     * retry/refresh while the guard still swallows the next load.
     */
    fun loadHistory() {
        if (_state.value.loadInFlight) return
        historyGeneration += 1
        val generation = historyGeneration
        olderLoadJob?.cancel()
        olderLoadJob = null
        loadedServerMessages = 0
        _state.update {
            it.copy(
                loadInFlight = true,
                history = MessageHistoryState.Loading,
                turnFailure = null,
                canLoadOlder = false,
                loadingOlder = false,
                olderLoadError = null
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
            if (generation != historyGeneration) return@launch
            if (result is MessageHistoryResult.Success) {
                loadedServerMessages = result.page.returned
            }
            _state.update {
                it.copy(
                    history = when (result) {
                        is MessageHistoryResult.Success -> {
                            dropRehydratedUserLineShadowedByHistory(result.page.messages)
                            MessageHistoryState.Ready(result.page.messages)
                        }
                        is MessageHistoryResult.Failure ->
                            MessageHistoryState.Failed(result.error)
                    },
                    canLoadOlder = (result as? MessageHistoryResult.Success)
                        ?.page?.hasMoreOnServer == true
                )
            }
        }
    }

    /**
     * User-triggered older-page load (the only trigger; no automatic
     * retry loop). Guarded so at most one older read is in flight and so
     * it only starts when the server proved more history exists.
     *
     * The offset is [loadedServerMessages], the raw row count consumed by
     * successful history reads. Local/streamed turn lines are deliberately
     * not part of it. The page is merged in front of the visible history
     * by [MessageHistoryPaging]; its raw `returned` count advances the
     * offset even when hidden rows made the visible count smaller.
     *
     * A failure leaves the main history untouched and only publishes
     * [ChatUiState.olderLoadError], so the user can retry explicitly.
     */
    fun loadOlderMessages() {
        val current = _state.value
        if (current.loadingOlder || !current.canLoadOlder || current.loadInFlight) return
        val ready = current.history as? MessageHistoryState.Ready ?: return
        val offset = loadedServerMessages
        val generation = historyGeneration
        _state.update { it.copy(loadingOlder = true, olderLoadError = null) }
        val job = scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    historySource.loadOlderHistory(profileId, sessionId, offset)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MessageHistoryResult.Failure(MessageHistoryError.Network)
            }
            // A history reload (including a session-key switch, which
            // replaces this view model) makes this result stale.
            if (generation != historyGeneration) return@launch
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is MessageHistoryResult.Success -> {
                    val merged = MessageHistoryPaging.mergeOlder(ready.messages, result.page)
                    loadedServerMessages += result.page.returned
                    _state.update {
                        it.copy(
                            loadingOlder = false,
                            olderLoadError = null,
                            history = MessageHistoryState.Ready(merged.messages),
                            canLoadOlder = merged.hasMore
                        )
                    }
                }
                is MessageHistoryResult.Failure -> {
                    // Keep canLoadOlder and the visible history: the user
                    // may deliberately retry.
                    _state.update {
                        it.copy(loadingOlder = false, olderLoadError = result.error)
                    }
                }
            }
        }
        olderLoadJob = job
    }

    /**
     * Removes the rehydrated turn rows once the loaded server history
     * already records the same turn. The server stores the user message when
     * the run starts and the assistant answer when it ends, so a screen
     * re-created mid-turn would otherwise render the user's own message (and,
     * once the run finished before the load resolved, the answer) twice.
     *
     * Deliberately narrow: it only ever touches rows this view model
     * rehydrated (the rehydrated user row and the rows after it, which are
     * exactly this turn's), and only when the history's NEWEST user row is an
     * exact (role, content) match. While the turn is still running, the
     * history ends with that user row, so only the user line is dropped and
     * the live partial answer stays; once the history also carries a later
     * row (the recorded answer), the whole rehydrated turn is dropped because
     * the history is authoritative.
     */
    private fun dropRehydratedUserLineShadowedByHistory(messages: List<HermesChatMessage>) {
        val key = rehydratedUserLineKey ?: return
        val rehydratedIndex = _state.value.localLines.indexOfFirst { it.key == key }
        if (rehydratedIndex < 0) return
        val localUser = _state.value.localLines[rehydratedIndex]
        val newestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (newestUserIndex < 0) return
        if (messages[newestUserIndex].content != localUser.content) return
        val turnRecordedInFull = newestUserIndex < messages.lastIndex
        rehydratedUserLineKey = null
        _state.update { current ->
            current.copy(
                localLines = if (turnRecordedInFull) {
                    current.localLines.take(rehydratedIndex)
                } else {
                    current.localLines.filterNot { it.key == key }
                }
            )
        }
    }

    /**
     * Restores the persisted ordinary draft for the active key. Zero is the
     * only generation with no newer local intent: any local draft mutation
     * bumps [draftGeneration], so a delayed stored value must never overwrite
     * newer local intent.
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
     * debounced, latest-value persist for the active key.
     * Ignored while a turn is active (the composer is disabled then).
     */
    fun onDraftChange(value: String) {
        if (_state.value.turnActive) return
        _state.update { it.copy(draft = value) }
        draftGeneration += 1
        chatDraftRepository.persistDraft(profileId, sessionId, value)
    }

    /**
     * Records one change of the run-steer input. The steer
     * draft is separate from the ordinary composer draft and is never
     * persisted: it is only visible for an active, run-bound turn.
     */
    fun onSteerDraftChange(value: String) {
        _state.update { it.copy(steerDraft = value) }
    }

    /**
     * Moves exactly ONE recoverable steer — the item at position [index] —
     * into the ordinary new-message composer. It never sends
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
     * Clears the ordinary draft and its stored state together. Used exactly
     * where the composer is cleared on a successful send (`RunStarted`) and
     * on a back that leaves a running turn, so the just-sent text is never
     * re-persisted as a draft.
     */
    private fun clearDraftState() {
        _state.update { it.copy(draft = "") }
        draftGeneration += 1
        chatDraftRepository.clearDraft(profileId, sessionId)
    }

    /**
     * The "refresh history" action after a failed turn: resets the ephemeral
     * local lines, the turn failure and the start flag, and the run-specific
     * stop/steer state of the finished turn, then re-runs the idempotent
     * history read. The POST is never resent.
     */
    fun refreshHistory() {
        // An uncertain run is NOT reconcilable by a bare history refresh
        // (that would clear the screen state without releasing the app-wide
        // ownership). The explicit reconciliation is the only path.
        if (_state.value.turnUncertain) return
        rehydratedUserLineKey = null
        _state.update {
            it.copy(
                localLines = emptyList(),
                turnFailure = null,
                runStarted = false,
                boundRunId = null,
                pendingApproval = null,
                turnUncertain = false,
                reconcileInFlight = false,
                reconcileFailed = false,
                reconcileStillActive = false,
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
     * Dismisses the pending approval dialog. Invoked by the
     * dialog only after a `Resolved` decision; the decision POST itself is
     * owned by the dialog's own composition scope, not by this view model.
     * The app-level pending-approval bookkeeping is cleared together, so a
     * re-attached view model does not re-show a request the user already
     * resolved.
     */
    fun dismissApproval() {
        val request = _state.value.pendingApproval ?: return
        _state.update { it.copy(pendingApproval = null) }
        runController.clearPendingApproval(
            profileId = profileId,
            sessionId = sessionId,
            runId = request.runId,
            requestId = request.requestId
        )
    }

    /**
     * Drops the app-level record of an approval the server reports as no
     * longer pending (409 `NoPending`: already answered elsewhere or timed
     * out). The authoritative answer is terminal, so a re-created screen
     * must not resurrect the dialog - but THIS composition keeps its local
     * request state, so the user still sees the short "already answered"
     * note it is showing. Deliberately never called for a transient failure:
     * an ambiguous decision must stay resolvable after a reconnect.
     */
    fun finishApproval() {
        val request = _state.value.pendingApproval ?: return
        runController.clearPendingApproval(
            profileId = profileId,
            sessionId = sessionId,
            runId = request.runId,
            requestId = request.requestId
        )
    }

    /**
     * Manual reconciliation of an UNCERTAIN run. Reads the server-side
     * status of the confirmed run (`GET /v1/runs/{run_id}`) and releases the
     * app-wide ownership ONLY when the server proves a terminal status. A
     * still-active or unreadable status changes nothing: the ownership is
     * kept (no second POST, no implicit stop) and the honest recovery notice
     * stays. Requires a confirmed, non-blank run ID; without one there is no
     * safe recovery path and the call is a no-op.
     */
    fun reconcileUncertainRun() {
        val current = _state.value
        if (!current.turnUncertain || current.reconcileInFlight) return
        val runId = current.boundRunId?.takeIf { it.isNotBlank() } ?: return
        _state.update {
            it.copy(
                reconcileInFlight = true,
                reconcileFailed = false,
                reconcileStillActive = false,
                lostRun = null,
                lostRunWithoutKey = false
            )
        }
        scope.launch {
            val result = try {
                runStatusRequester.getRunStatus(profileId, runId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RunStatusResult.Failure(RunStatusError.Network)
            }
            // A late result must never mutate a screen that was already left
            // (back) or disposed.
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            // A confirmed 404 for this very run means the server no longer
            // knows it (Hermes forgets finished runs after an hour and sweeps
            // orphaned ones): it cannot be running, so it settles like a
            // terminal status. Every other failure keeps the ownership.
            val over = when (result) {
                is RunStatusResult.Known -> result.status.isTerminal
                is RunStatusResult.Failure -> result.error == RunStatusError.NotFound
            }
            when {
                over -> {
                    // The server proved the run over: release the ownership
                    // authoritatively and reconcile the visible turn from the
                    // authoritative history. The chat POST is never resent.
                    if (runController.confirmTerminalForUncertainRun(profileId, sessionId, runId)) {
                        releaseUncertainRun()
                    } else {
                        _state.update { it.copy(reconcileInFlight = false) }
                    }
                }
                result is RunStatusResult.Known && result.status == RunServerStatus.UNKNOWN &&
                    result.lostRun != null -> {
                    // The core lost the run and blocks the chat until the
                    // user closes it explicitly; nothing happens on its own.
                    _state.update { it.copy(reconcileInFlight = false, lostRun = result.lostRun) }
                }
                result is RunStatusResult.Known && result.status == RunServerStatus.UNKNOWN -> {
                    _state.update { it.copy(reconcileInFlight = false, lostRunWithoutKey = true) }
                }
                result is RunStatusResult.Known -> {
                    _state.update {
                        it.copy(reconcileInFlight = false, reconcileStillActive = true)
                    }
                }
                else -> {
                    _state.update {
                        it.copy(reconcileInFlight = false, reconcileFailed = true)
                    }
                }
            }
        }
    }

    /**
     * The user's confirmed "release in the app" for an uncertain run that
     * can no longer be proven over. Local only (see
     * [RunSessionController.releaseUncertainByUser]); the history is read
     * again and the draft is kept.
     */
    fun releaseUncertainLocally() {
        if (!_state.value.releaseVisible) return
        if (runController.releaseUncertainByUser(profileId, sessionId)) {
            releaseUncertainRun()
            return
        }
        // Another observer may already have released the old slot. Settle
        // only this screen; never release a replacement or another session.
        val active = runController.activeRun.value
        if (active == null || active.profileId != profileId || active.sessionId != sessionId) {
            releaseUncertainRun()
        } else {
            _state.update {
                it.copy(
                    turnUncertain = active.uncertain,
                    runStarted = active.runId != null,
                    boundRunId = active.runId,
                    reconcileInFlight = false,
                    reconcileFailed = false,
                    reconcileStillActive = false,
                    lostRun = null,
                    lostRunWithoutKey = false,
                    resolveInFlight = false
                )
            }
        }
    }

    /**
     * The user's explicit "close as cancelled" for a run the core lost:
     * one `resolve-unknown` POST (never repeated). On success the core has
     * ended the turn as interrupted, so it settles like a terminal status.
     * A stale key (the run moved on) drops the offer; the user can check the
     * status again.
     */
    fun resolveLostRun() {
        val current = _state.value
        val lostRun = current.lostRun ?: return
        if (!current.turnUncertain || current.resolveInFlight) return
        val runId = current.boundRunId?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(resolveInFlight = true, reconcileFailed = false) }
        scope.launch {
            val result = try {
                runUnknownResolver.resolve(profileId, runId, lostRun)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RunResolveResult.Failure(RunResolveError.Failed)
            }
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                RunResolveResult.Resolved ->
                    if (runController.confirmTerminalForUncertainRun(profileId, sessionId, runId)) {
                        releaseUncertainRun()
                    } else {
                        _state.update { it.copy(resolveInFlight = false) }
                    }
                is RunResolveResult.Failure -> _state.update {
                    it.copy(
                        resolveInFlight = false,
                        lostRun = null,
                        reconcileFailed = result.error == RunResolveError.Failed
                    )
                }
            }
        }
    }

    /**
     * Settles an uncertain turn after a reconciliation proved the server
     * run terminal. Mirrors the authoritative `AlreadyTerminal` path: the
     * ephemeral turn lines and the run binding are reset, the recoverable
     * steer draft is preserved, and the idempotent history read is re-run.
     */
    private fun releaseUncertainRun() {
        val leftoverSteerDraft = _state.value.steerDraft.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(
                turnUncertain = false,
                turnFailure = null,
                reconcileInFlight = false,
                reconcileFailed = false,
                reconcileStillActive = false,
                lostRun = null,
                resolveInFlight = false,
                lostRunWithoutKey = false,
                runStarted = false,
                boundRunId = null,
                localLines = emptyList()
            )
        }
        leftoverSteerDraft?.let { appendSteerRecovery(it) }
        // The history read is launched BEFORE the turn settles: settling
        // cancels the in-flight collectors of the finished run.
        loadHistory()
        turnSettled()
    }

    /**
     * Starts exactly one streaming turn for the validated composer draft
     * (background continuity). The local user line appears
     * immediately after the accepted submit; the draft is cleared only on
     * `RunStarted`. A failure before the confirmed start keeps the draft and
     * drops the premature user line.
     *
     * The stream AND the run foreground service are owned app-level by
     * [runController]: this method is the user's send tap, so the foreground
     * start inside the controller is always eligible (the app is in the
     * foreground), and the run survives this screen leaving composition.
     * This method only prepares the screen state and hands the turn over.
     */
    /**
     * Records one picker result. The provider's metadata is read through
     * the [ChatAttachmentSource] seam and gated by [ChatAttachmentRules]
     * before anything is stored: an unsupported type, an over-sized file or an
     * unreadable URI yields a closed error and no draft. Nothing is uploaded
     * here - the bytes are only read at send time.
     */
    fun onAttachmentPicked(contentUri: String) {
        val current = _state.value
        if (current.turnActive || current.attachmentBusy) return
        if (contentUri.isBlank()) {
            _state.update { it.copy(attachmentError = ChatAttachmentError.Unreadable) }
            return
        }
        _state.update { it.copy(attachmentBusy = true, attachmentError = null) }
        scope.launch {
            val metadata = try {
                withContext(ioDispatcher) { attachmentSource.readMetadata(contentUri) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (metadata == null) {
                _state.update {
                    it.copy(attachmentBusy = false, attachmentError = ChatAttachmentError.Unreadable)
                }
                return@launch
            }
            val validated = ChatAttachmentRules.validate(
                localId = nextLocalKey(),
                contentUri = contentUri,
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes
            )
            val draft = validated.getOrNull()
            if (draft == null) {
                val error = (validated.exceptionOrNull() as? ChatAttachmentRules.AttachmentRejected)
                    ?.error
                    ?: ChatAttachmentError.Unreadable
                _state.update { it.copy(attachmentBusy = false, attachmentError = error) }
                return@launch
            }
            ChatAttachmentDraftHolder.put(profileId, sessionId, draft)
            _state.update { it.copy(attachmentBusy = false, attachment = draft, attachmentError = null) }
        }
    }

    /** Drops the picked attachment (and its process-scoped rehydration entry). */
    fun removeAttachment() {
        if (_state.value.attachment == null) return
        ChatAttachmentDraftHolder.clear(profileId, sessionId)
        _state.update { it.copy(attachment = null, attachmentError = null) }
    }

    fun send() {
        val current = _state.value
        if (current.turnActive) return
        if (current.history !is MessageHistoryState.Ready) return
        // A failure before the confirmed start does not block a new,
        // deliberate submit; only an active turn or a failure after the
        // confirmed start does.
        if (current.turnFailure != null && current.runStarted) return
        val attachment = current.attachment
        val message = if (attachment == null) {
            ChatMessageValidator.validate(current.draft).getOrNull() ?: return
        } else {
            // An attachment-only turn is legitimate: the text may be blank,
            // and the final body is validated once the image is encoded.
            ChatMessageValidator.validateOptional(current.draft).getOrNull() ?: return
        }
        // Fast path (UX only): if a run already owns the app-wide slot,
        // reject before any optimistic local state - no premature user line,
        // no composer disable, no notification-permission prompt. The
        // controller's atomic reservation stays the authoritative boundary
        // for the race (handled by [onStartConflict] below).
        if (runController.activeRun.value != null) {
            _state.update { it.copy(startConflict = true) }
            return
        }
        // A new turn starts without stream ownership: ownership is taken only
        // when the controller actually starts the run (or a matching run is
        // rehydrated), so foreign events can never be misattributed.
        ownsRun = false
        _state.update {
            it.copy(
                turnActive = true,
                turnFailure = null,
                startConflict = false,
                reconnecting = false,
                thinking = false,
                // A new turn starts with a fresh reconciliation state: no leftover
                // uncertainty or reconciliation result of the finished run.
                turnUncertain = false,
                reconcileInFlight = false,
                reconcileFailed = false,
                reconcileStillActive = false,
                runStarted = false,
                // A new turn starts with a fresh stop state: the previous run
                // ID and any in-flight stop belong to the finished turn and
                // must not leak into this one.
                boundRunId = null,
                stopInFlight = false,
                stopFailed = false,
                // A new turn also starts with a fresh steer state.
                // The recoverable text is deliberately KEPT.
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
        activeUserLineKey = userLineKey
        _state.update {
            it.copy(
                localLines = it.localLines + TimelineRow(
                    key = userLineKey,
                    role = MessageRole.USER,
                    // An attachment-only turn has no text to show, so the row
                    // falls back to the attachment's display name (display
                    // only, never sent, never a path).
                    content = message.ifBlank { attachment?.displayName.orEmpty() }
                )
            )
        }
        terminalPendingSteer = null
        if (attachment == null) {
            // Synchronous on purpose: the controller starts the foreground
            // service while the app is still in the foreground. The VALIDATED
            // (trimmed) text is handed over - the same text the visible user row
            // shows - so a re-created view model rehydrates the identical line.
            ownsRun = true
            when (runController.startTurn(profileId, sessionId, message)) {
                StartTurnResult.Started -> Unit
                StartTurnResult.ActiveRunConflict -> onStartConflict()
            }
            return
        }
        // The attachment bytes are read and encoded BEFORE the POST. On
        // any failure no request is sent at all, the turn stays inactive and
        // the draft (text + attachment) is kept, so the user can retry or
        // remove it deliberately.
        scope.launch {
            // The read outcome is closed: an attachment that exceeds the
            // limit is reported as too large even when the provider never
            // reported a size, while a broken read stays "unreadable".
            val readResult = try {
                withContext(ioDispatcher) {
                    attachmentSource.readInlineDataUrlResult(
                        contentUri = attachment.contentUri,
                        mimeType = attachment.mimeType,
                        maxBytes = ChatAttachmentRules.MAX_IMAGE_BYTES
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ChatAttachmentReadResult.Unreadable
            }
            val outbound = (readResult as? ChatAttachmentReadResult.DataUrl)?.let {
                ChatOutboundMessage.create(message, listOf(it.value))
            }
            if (outbound == null) {
                val error = when (readResult) {
                    ChatAttachmentReadResult.TooLarge -> ChatAttachmentError.TooLarge
                    else -> ChatAttachmentError.Unreadable
                }
                _state.update {
                    it.copy(turnActive = false, attachmentError = error)
                }
                dropPrematureUserLine()
                return@launch
            }
            // Ownership is taken only now, immediately before the controller
            // claims the slot: no event of a foreign run can be attributed to
            // this screen while the attachment bytes were still being read.
            ownsRun = true
            when (
                runController.startTurn(
                    profileId = profileId,
                    sessionId = sessionId,
                    message = outbound.text,
                    imageDataUrls = outbound.imageDataUrls
                )
            ) {
                StartTurnResult.Started -> Unit
                StartTurnResult.ActiveRunConflict -> onStartConflict()
            }
        }
    }

    /**
     * Handles one event of the active run: the verified baseline state
     * transitions, driven by the app-level controller instead of a
     * screen-lifetime collector. Notification side effects (approval
     * requested, run completed) are owned by the controller, so they fire
     * even when no chat screen is open.
     *
     * Events are attributed by this screen's own [ChatUiState.turnActive]
     * flag — deliberately NOT by the controller's active run: the stream
     * events cross a buffered [RunSessionController.events] flow, so an
     * event may be consumed only after the controller has already moved
     * on with its own bookkeeping (the terminal event always arrives
     * after the controller cleared its slot). [send] sets the flag before
     * any event of the new turn can arrive, and [turnSettled] clears it
     * exactly once, so at most one screen ever processes a given run
     * (single-slot client).
     */
    private fun handleRunEvent(event: ChatTurnEvent) {
        val current = _state.value
        if (!current.turnActive || !ownsRun) return
        // Any progress after a re-attach announcement means the
        // stream is flowing again; only the announcement itself sets
        // the transient status.
        if (event !is ChatTurnEvent.StreamReconnecting && current.reconnecting) {
            _state.update { it.copy(reconnecting = false) }
        }

        when (event) {
            is ChatTurnEvent.StreamReconnecting -> {
                // The stream was interrupted by a transient network
                // failure and is being re-attached to the same run:
                // the turn stays active, nothing is settled.
                _state.update { s -> s.copy(reconnecting = true) }
            }
            is ChatTurnEvent.RunStarted -> {
                // The server-confirmed run ID is the only value the
                // stop control may use; it is bound here and never
                // rendered or logged. Successful send handoff:
                // clear the composer AND its stored state
                // together.
                _state.update { s -> s.copy(runStarted = true, boundRunId = event.runId) }
                // The local line is no longer premature from here on.
                activeUserLineKey = null
                clearDraftState()
                // The run is confirmed, so the sent attachment is cleared
                // from the composer and from the process-scoped rehydration
                // entry - it can never be sent twice.
                if (_state.value.attachment != null) {
                    ChatAttachmentDraftHolder.clear(profileId, sessionId)
                    _state.update { s -> s.copy(attachment = null, attachmentError = null) }
                }
            }
            is ChatTurnEvent.AssistantStarted -> {
                // Do not render an empty bubble: a tool may run
                // before the first assistant token.
            }
            is ChatTurnEvent.Reasoning -> {
                _state.update { s -> s.copy(thinking = event.active) }
            }
            is ChatTurnEvent.AssistantDelta -> {
                _state.update { s ->
                    val last = s.localLines.lastOrNull()
                    s.copy(
                        thinking = false,
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
                        thinking = false,
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
                // A second request while one is still pending is a protocol
                // contradiction: the actionable request must never be
                // overwritten, so the turn fails with the closed
                // `IncompatibleApi` code. This is a LOCAL contradiction,
                // not a confirmed server stop, so the possibly-live server
                // run must NOT be released - the controller keeps the
                // ownership and marks it uncertain (no second POST, no
                // automatic approval). The notification for the FIRST
                // request was already posted by the controller.
                if (current.pendingApproval != null) {
                    runController.markTurnUncertain(profileId, sessionId)
                    _state.update {
                        it.copy(
                            pendingApproval = null,
                            turnFailure = ChatStreamError.IncompatibleApi,
                            turnUncertain = true,
                            reconnecting = false
                        )
                    }
                } else {
                    _state.update { s -> s.copy(pendingApproval = event.request) }
                }
            }
            is ChatTurnEvent.ToolActivity -> {
                // Generic status only: never a tool name or payload
                // (those exist only for server history lines).
                _state.update { s ->
                    s.copy(
                        thinking = false,
                        localLines = s.localLines + TimelineRow(
                            key = nextLocalKey(),
                            role = MessageRole.TOOL,
                            content = event.toolName?.let {
                                namedToolActivityLabel.format(it)
                            } ?: toolActivityLabel,
                            // The redacted name only (for the tool group
                            // summary), never a payload.
                            toolName = event.toolName,
                            localTool = true
                        )
                    )
                }
            }
            is ChatTurnEvent.TurnCompleted -> {
                // The run-completed notification is posted by the
                // controller (background policy + duplicate guard); only the
                // screen state is settled here.
                _state.update { s -> s.copy(pendingApproval = null) }
                val pending = event.pendingSteer
                if (pending != null) {
                    // A `pending_steer` carried by
                    // `run.completed` is surfaced through the same
                    // recoverable state, as its own separate item.
                    terminalPendingSteer = pending
                    appendSteerRecovery(pending)
                }
                turnSettled()
            }
            is ChatTurnEvent.TurnFailed -> {
                // The controller only keeps the run slot for a failure
                // whose server outcome is UNKNOWN (a confirmed run plus a
                // transport/protocol failure, or an unconfirmed start that
                // may already have been accepted). In that case this screen
                // stays on the uncertain run: the confirmed binding is kept
                // (so a manual reconciliation can use it), the turn stays
                // active (composer disabled - no second POST) and a generic
                // recovery notice is shown. The run is NOT released locally.
                if (runController.activeRun.value?.uncertain == true) {
                    _state.update { s ->
                        s.copy(
                            pendingApproval = null,
                            turnFailure = event.error,
                            turnUncertain = true,
                            reconnecting = false
                        )
                    }
                    dropPrematureUserLine()
                } else {
                    _state.update {
                        s -> s.copy(pendingApproval = null, turnFailure = event.error)
                    }
                    dropPrematureUserLine()
                    turnSettled()
                }
            }
        }
    }

    /**
     * Settles the active turn exactly once, on its terminal event or a
     * client-side protocol failure: releases the composer, cancels any
     * in-flight stop/steer request of the finished run (a late callback must
     * never mutate the screen state), and applies the
     * same-request duplicate suppression for the recoverable steer text.
     */
private fun turnSettled() {
        if (!_state.value.turnActive) return
        ownsRun = false
        _state.update {
            it.copy(
                turnActive = false,
                turnUncertain = false,
                reconcileInFlight = false,
                reconcileFailed = false,
                reconcileStillActive = false,
                pendingApproval = null,
                backPending = false,
                reconnecting = false
            )
        }
        stopJob?.cancel()
        steerJob?.cancel()
        stopJob = null
        steerJob = null
        // Capture the in-flight request's EXACT submitted text and the
        // leftover draft BEFORE clearing the flags: they are
        // only known at this moment.
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
        // Same-request duplicate suppression: if the
        // in-flight request was already reported by this turn's terminal
        // `pending_steer`, it was appended exactly once already. The
        // comparison uses the exact validated/trimmed wire text — never the
        // raw draft.
        val sameRequestAsTerminalPending =
            inFlightSteerText != null &&
                terminalPendingSteer != null &&
                inFlightSteerText == terminalPendingSteer
        if (leftoverSteerDraft != null && !sameRequestAsTerminalPending) {
            appendSteerRecovery(leftoverSteerDraft)
        }
        terminalPendingSteer = null
        activeUserLineKey = null
    }

    /**
     * Drops the premature local user line of the active turn, keeping the
     * draft in the composer. Only applies before the confirmed `run.started`:
     * a message the server confirmed is real and stays on the timeline.
     */
    private fun dropPrematureUserLine() {
        val key = activeUserLineKey ?: return
        if (!_state.value.runStarted) {
            _state.update { s ->
                s.copy(localLines = s.localLines.filter { it.key != key })
            }
        }
    }

    /**
     * Rolls back an optimistic send that the controller rejected because
     * a run already owns the app-wide slot. NOTHING was POSTed and nothing on
     * the server changed. The rejected turn leaves no pending stream, no
     * optimistic local user line and no bound run; the draft AND the picked
     * attachment are preserved exactly, and a generic, closed notice is shown.
     * The notice clears on its own once the global run is terminal; the
     * waiting draft is never sent automatically.
     */
    private fun onStartConflict() {
        ownsRun = false
        val key = activeUserLineKey
        _state.update { s ->
            s.copy(
                turnActive = false,
                runStarted = false,
                boundRunId = null,
                reconnecting = false,
                pendingApproval = null,
                startConflict = true,
                localLines = if (key == null) s.localLines else s.localLines.filterNot { it.key == key }
            )
        }
        activeUserLineKey = null
    }

    /**
     * Starts exactly one stop request for the server-confirmed run of the
     * active turn. The in-flight flag is set before the request
     * starts: fast multi-taps never open a second request. `Accepted` only
     * marks the request; nothing is set optimistically. `AlreadyTerminal`
     * runs the reconciliation path; `Failure` leaves one deliberate retry.
     */
    fun stop() {
        val current = _state.value
        if (!current.turnActive) return
        // While the run outcome is unknown the stop control is hidden and
        // no write is issued from here; recovery is an explicit reconciliation.
        if (current.turnUncertain) return
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
            // (back) or disposed.
            if (coroutineContext[Job]?.isCancelled == true) return@launch
            when (result) {
                is RunStopResult.Accepted -> {
                    // Accepted only means the server took the stop. Nothing is
                    // set optimistically: the stream runs on until its own
                    // terminal event settles the turn.
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
                    // The history read is launched BEFORE the turn settles:
                    // settling cancels this in-flight stop job itself.
                    loadHistory()
                    runController.cancelTurn(runId)
                    turnSettled()
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
     * active turn. The in-flight flag is set before the request
     * starts: fast multi-taps never open a second request. [RunSteerResult]
     * `Queued` acknowledges and clears the input; `NotAccepting` preserves the
     * text as a recoverable item; `Failure` keeps the text for one deliberate
     * retry.
     */
    fun steer() {
        val current = _state.value
        if (!current.turnActive) return
        // No steer write while the run outcome is unknown (no live
        // transport and the server status is not confirmed).
        if (current.turnUncertain) return
        if (current.steerInFlight) return
        val runId = current.boundRunId?.takeIf { it.isNotBlank() } ?: return
        val text = ChatMessageValidator.validate(current.steerDraft).getOrNull() ?: return
        // Record the EXACT validated/trimmed text actually sent, not the
        // mutable/raw steer draft.
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
            // the submitted-text tracking is cleared on EVERY result, whatever
            // the outcome.
            _state.update { it.copy(steerSubmittedText = null) }
            // A late result must never mutate a screen that was already left
            // (back) or disposed.
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
     * the visible button (background continuity):
     * with no active turn it navigates back immediately; with an active turn
     * it clears the in-turn draft and the run-specific steer state, and then
     * navigates back exactly once. The run itself is NOT interrupted: it is
     * owned app-level by the controller and keeps streaming in the
     * background (the foreground service keeps the process unfrozen). Fast
* double taps are ignored while the back is in flight.
     */
    fun handleBack() {
        val current = _state.value
        when {
            !current.turnActive -> onBack()
            current.backPending -> Unit
            else -> {
                _state.update { it.copy(backPending = true) }
                // Back leaves the screen: clear the composer AND its stored
                // state so the in-turn draft is not re-persisted as a draft
                // for the left session. The run continues in
                // the background.
                clearDraftState()
                // The run-specific steer state (incl. the recoverable items)
                // belongs to the left run.
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

    /**
     * Tears the view model down on a key switch or leaving composition
     * (background continuity): cancels any in-flight
     * stop/steer request and the event observer of the previous session, and
     * deterministically flushes the pending ordinary draft so it is neither
     * lost nor able to land under the new key. The active RUN (if any) is
     * NOT affected: it is owned app-level by the controller and keeps
     * streaming. No state is published after cancellation.
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
 * A no-op notification coordinator for the run session controller in tests
 * (unit and instrumented): it never posts anything, so run behavior is
 * unaffected when no notification wiring is supplied. The no-op poster plus
 * a permanently-foreground signal make it a double no-op: the policy alone
 * already blocks every post.
 */
internal val NoOpNotificationCoordinator: NotificationCoordinator =
    NotificationCoordinator(
        poster = NotificationPoster { },
        isForeground = { true },
        serverNameProvider = { "" },
        fallbackTitle = ""
    )
