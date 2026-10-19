package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import kotlinx.coroutines.CancellationException
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seam for starting and stopping the run foreground service.
 *
 * The implementation (Android adapter) starts a `dataSync` foreground
 * service whose notification keeps the app process out of the cached
 * state while an agent run is active, so the cached-app freezer cannot
 * terminate the run's SSE sockets. The domain never sees the service
 * itself: it only asks for the background-continuity effect.
 */
interface RunForegroundServiceStarter {
    /**
     * Starts (or re-asserts) the foreground state for the run identified
     * by [profileId]/[sessionId]. Must be called from the foreground:
     * the app calls it synchronously in the user's send action.
     */
    fun start(profileId: String, sessionId: String)

    /** Stops the foreground state. Idempotent. */
    fun stop()
}

/**
 * App-level owner of the single active agent run (background continuity).
 *
 * Orsik is a single-slot client: at most one run is active at any time.
 * This controller owns the run's STREAM (not its UI state): the streaming
 * source is collected on an app-lifetime scope, so the SSE connection
 * survives the chat screen leaving composition, an activity recreation and
 * the app going to the background. The chat view model only observes
 * [events] and renders them; it no longer decides when the transport dies.
 *
 * Foreground-service lifecycle (user-specified):
 * - [startTurn] starts the foreground service immediately, while the app
 *   is still in the foreground (the user's send tap), so the Android 14+
 *   background-start restriction can never apply.
 * - The service stays up across `approval.request`: the server needs the
 *   live SSE connection while the user decides.
 * - The service is stopped whenever the stream is no longer consumed: on
 *   an authoritative terminal (`run.completed`, the server's terminal
 *   `error` frame, a confirmed stop), on a proven pre-run rejection, and on
 *   an UNKNOWN outcome. On an unknown outcome the logical run
 *   ownership deliberately survives without a live transport (see
 *   [markTurnUncertain]); it is never released by a local transport or
 *   protocol failure.
 *
 * Notification side effects (run completed, approval requested) are owned
 * here as well, because they must fire even when no chat screen is open.
 * The [NotificationCoordinator] applies the background-only policy and the
 * duplicate guard; no run content is passed beyond the closed types.
 */
class RunSessionController(
    private val chatSource: StreamingChatSource,
    private val serviceStarter: RunForegroundServiceStarter,
    private val notificationCoordinator: NotificationCoordinator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    // Dispatcher for the notification side effects (server-label lookup is
    // storage + JSON parsing). Defaults to [dispatcher] so tests stay on their
    // controlled dispatcher; production injects Dispatchers.IO.
    private val ioDispatcher: CoroutineDispatcher = dispatcher,
    // Durable lease of the confirmed run, written and read on
    // [ioDispatcher]. The default keeps the controller purely in-memory.
    private val leaseStore: RunLeaseStore = NoOpRunLeaseStore,
    // Whether a restored lease still belongs to a known profile (a
    // deleted profile has no credential, so its run could never be
    // reconciled and would block the slot forever). Called on [ioDispatcher].
    private val isLeaseRestorable: (RunLease) -> Boolean = { true }
) {
    /**
     * Closed outcome of [startTurn].
     *
     * The caller gets exactly one of two values instead of an exception or
     * a dynamic server detail: either it reserved the single global run
     * slot and started the run ([Started]), or a run is already active
     * somewhere in the app ([ActiveRunConflict]) and NOTHING was started,
     * stopped or changed. No run identity (run ID, session ID, profile ID)
     * ever crosses this boundary: the UI only needs "started" vs. "busy".
     */
    sealed interface StartTurnResult {
        /** The slot was free, was reserved atomically and the run started. */
        data object Started : StartTurnResult

        /**
         * A run is already active anywhere in the app — the same session,
         * another session, another profile, reconnecting, waiting for an
         * approval or not yet confirmed by the server. The existing run is
         * left completely untouched.
         */
        data object ActiveRunConflict : StartTurnResult
    }

    /**
     * The single active run: the small, closed in-memory state a chat
     * screen needs to re-attach to a running turn.
     *
     * [runId] is bound only after the server confirmed the run
     * (`RunStarted`). [userMessage] is the validated user text of the turn
     * and [assistantText] the composed assistant text received so far, so a
     * view model re-created by an Activity/configuration change can rebuild
     * the visible turn instead of starting from a blank screen.
     * [reconnecting] mirrors the transient re-attach and [pendingApproval]
     * the request the user has not decided yet (both restored when a view
     * model re-attaches to a running session).
     *
     * Only the run identity is persisted, as a [RunLease]: after a
     * process death the slot comes back as UNCERTAIN with its [runId] and
     * nothing else. No text or tool data is persisted, and no
     * server payload beyond the already UI-safe values crosses this type:
     * no command, arguments, path, raw tool output, exception text, URL or
     * credential. [assistantText] is exactly the text the user already sees.
     *
     * `status` is a derived view of the flags, so the UI has one closed
     * status value without a second state machine.
     */
    data class ActiveRun(
        val profileId: String,
        val sessionId: String,
        val userMessage: String = "",
        val assistantText: String = "",
        /** Safe tool identifiers of the turn, in arrival order (redacted domain). */
        val toolNames: List<String> = emptyList(),
        val runId: String? = null,
        val reconnecting: Boolean = false,
        val pendingApproval: ApprovalRequest? = null,
        /**
         * The client lost the transport (or the reliable event
         * processing) while the server run may still be running. The
         * logical ownership is deliberately KEPT (no new POST, no
         * implicit server stop); only an explicit reconciliation may
         * release it. The foreground service is stopped, because there
         * is no active transport to keep alive.
         */
        val uncertain: Boolean = false
    ) {
        /** Closed, derived status of the active run. */
        val status: Status
            get() = when {
                uncertain -> Status.UNCERTAIN
                pendingApproval != null -> Status.WAITING_FOR_APPROVAL
                reconnecting -> Status.RECONNECTING
                else -> Status.RUNNING
            }

        /** Closed set of active-run statuses for the UI projection. */
        enum class Status { RUNNING, RECONNECTING, WAITING_FOR_APPROVAL, UNCERTAIN }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Guards the compound "is the global slot free? then reserve it" step
     * in [startTurn], so the check and the reservation are one indivisible
     * operation even if two callers invoke [startTurn] concurrently (race,
     * double tap, two screens).
     */
    private val slotLock = Any()
    private val _activeRun = MutableStateFlow<ActiveRun?>(null)
    private val _events = MutableSharedFlow<ChatTurnEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** The single active run, or null. */
    val activeRun: StateFlow<ActiveRun?> = _activeRun

    /**
     * Turn events of the active run. A view model collects this from its
     * own (screen-lifetime) scope; collecting it again after the terminal
     * event simply waits for the next run.
     */
    val events: SharedFlow<ChatTurnEvent> = _events

    // Handle of the active run's collector, used by [cancelTurn] and
    // [close]. [startTurn] writes it right after the lock-guarded
    // reservation; the reservation itself (the "at most one active run"
    // invariant) is fully serialized by [slotLock]. The small handoff
    // between reservation and this assignment is unreachable from the UI:
    // [cancelTurn] and [close] are only reached for an already-confirmed
    // run (a bound run ID), which the controller can only have after this
    // assignment, and every caller runs on the same dispatcher.
    @Volatile
    private var runJob: Job? = null

    /**
     * Identity of the turn currently owning the slot. A finishing
     * run's collector may still execute a late synchronous body after the
     * slot was released; the token makes such a late event a no-op instead
     * of letting it write one run's text into a successor's slot (the
     * slot is never replaced while active, but a terminal/cancel followed
     * by a new start must still isolate the two turns).
     */
    @Volatile
    private var slotTurnToken: Long = 0L

    /**
     * `true` until the persisted lease was read. While pending,
     * [startTurn] reports [StartTurnResult.ActiveRunConflict], so a send tap
     * racing the restore can never POST a second turn next to a run that is
     * still owned on the server. Guarded by [slotLock].
     */
    private var leaseRestorePending: Boolean = leaseStore !== NoOpRunLeaseStore

    init {
        if (leaseRestorePending) {
            scope.launch {
                restoreLease()
                persistLeaseChanges()
            }
        }
    }

    /**
     * Tries to start one streaming turn for [message] in [sessionId].
     *
     * Orsik is a single-slot client: the app-wide slot is owned by exactly
     * one run. If any run is already active — same session, another
     * session, another profile, reconnecting, waiting for an approval, or
     * not yet confirmed by the server — this returns [StartTurnResult.ActiveRunConflict]
     * and does NOTHING: no second chat POST, no cancellation or replacement
     * of the existing stream, no foreground-service re-start and no change
     * to [activeRun]. The old "startTurn replaces the active run" behavior
     * is deliberately gone: a Hermes run is independent of its
     * socket, so a local stream cancel would NOT reliably stop the server
     * run and must never silently drop its ownership.
     *
     * The check and the reservation are atomic ([slotLock]), so concurrent
     * or near-simultaneous start attempts yield exactly one [Started] and
     * one [ActiveRunConflict]. The foreground service is started
     * synchronously only for the winner, before the stream is opened — the
     * caller is the user's send tap, so the app is in the foreground and
     * the Android 14+ background-start restriction cannot apply.
     *
     * A new run may start again only after the slot was released by an
     * AUTHORITATIVE outcome: a real terminal state (`run.completed` or the
     * server's own terminal `error` frame), a proven pre-run rejection, or an
     * explicit, confirmed stop ([cancelTurn]). A local transport/protocol
     * failure does NOT release the slot: it is kept as an uncertain run
     * ([markTurnUncertain]) that only an explicit reconciliation
     * ([confirmTerminalForUncertainRun]) may release.
     */
    fun startTurn(
        profileId: String,
        sessionId: String,
        message: String,
        /**
         * Inline image data URLs of this turn's attachments. They travel
         * only in the initial POST body - never into the durable run state
         * (they are large and carry user content the UI already shows) and
         * never into a stream re-attach.
         */
        imageDataUrls: List<String> = emptyList()
    ): StartTurnResult {
        val token: Long
        synchronized(slotLock) {
            // The reservation is taken BEFORE any side effect, so a losing
            // caller can never start the service, open a socket or touch
            // the winner's state.
            if (leaseRestorePending || _activeRun.value != null) {
                return StartTurnResult.ActiveRunConflict
            }
            token = slotTurnToken + 1L
            slotTurnToken = token
            _activeRun.value = ActiveRun(
                profileId = profileId,
                sessionId = sessionId,
                userMessage = message
            )
        }
        // Only the winner reaches this point. The foreground service is
        // started synchronously in the user's send action (the app is in
        // the foreground), so a failed turn stops it again on its terminal
        // event and leaves no background footprint.
        serviceStarter.start(profileId, sessionId)
        val job = scope.launch {
            var terminalSeen = false
            try {
                if (imageDataUrls.isEmpty()) {
                    chatSource.streamTurn(profileId, sessionId, message)
                } else {
                    chatSource.streamTurnWithAttachments(profileId, sessionId, message, imageDataUrls)
                }.collect { event ->
                    if (event is ChatTurnEvent.TurnCompleted ||
                        event is ChatTurnEvent.TurnFailed
                    ) {
                        terminalSeen = true
                    }
                    handleEvent(profileId, sessionId, token, event)
                }
                // Defensive fail-closed: the source contract guarantees a
                // terminal event, but a stream that closes without one must
                // still settle as a generic network failure.
                if (!terminalSeen) {
                    handleEvent(
                        profileId,
                        sessionId,
                        token,
                        ChatTurnEvent.TurnFailed(ChatStreamError.Network)
                    )
                }
            } catch (e: CancellationException) {
                // [cancelTurn] or [close]: no terminal is emitted, and the
                // foreground service is NOT stopped here — the cancel/
                // teardown callers stop it themselves.
                throw e
            } catch (_: Exception) {
                // Defensive: the source contract guarantees a terminal
                // event; an unexpected throw must still fail the run closed.
                handleEvent(
                    profileId,
                    sessionId,
                    token,
                    ChatTurnEvent.TurnFailed(ChatStreamError.Network)
                )
            } finally {
                // Bookkeeping only: the release of the slot is decided
                // exclusively by the terminal handlers ([handleEvent])
                // and by [cancelTurn]/[markTurnUncertain]/[close]. A
                // collector that ends with an UNKNOWN outcome must leave
                // the slot owned ([handleEvent] marks it uncertain), so
                // this finally never clears the slot - it only drops its
                // own (now finished) job handle, guarded so it can never
                // clear a successor's handle.
                if (runJob === currentCoroutineContext()[Job]) {
                    runJob = null
                }
            }
        }
        runJob = job
        return StartTurnResult.Started
    }

    /**
     * AUTHORITATIVE release of the active run: the caller has a
     * server-confirmed terminal answer for exactly this run (e.g.
     * `RunStopResult.AlreadyTerminal`). Cancels the (possibly still
     * draining) local stream, stops the foreground service and clears
     * the single slot immediately.
     *
     * This must NOT be used for a local transport/protocol failure or
     * a contradictory event: those do not prove the server run ended.
     * Use [markTurnUncertain] for them.
     */
    fun cancelTurn() {
        val job = runJob
        runJob = null
        job?.cancel()
        serviceStarter.stop()
        _activeRun.value = null
    }

    /**
     * Keeps the logical run ownership but drops the dead transport after a
     * LOCAL contradiction or a transport/protocol failure: the server
     * run may still be alive, so nothing is started, stopped server-side or
     * POSTed. The slot stays owned in the [ActiveRun.Status.UNCERTAIN]
     * state, the foreground service is stopped (there is no active transport
     * to keep alive), and only an explicit reconciliation
     * ([confirmTerminalForUncertainRun]) or a confirmed authoritative
     * terminal may release it.
     *
     * A no-op when no run owns the slot. Strictly correlated to the current
     * slot's [profileId]/[sessionId]: a late or foreign contradiction can
     * never cancel a successor's transport or mark its run uncertain.
     */
    fun markTurnUncertain(profileId: String, sessionId: String) {
        synchronized(slotLock) {
            val current = _activeRun.value
            if (current == null || current.profileId != profileId || current.sessionId != sessionId) {
                return
            }
            val job = runJob
            runJob = null
            job?.cancel()
            _activeRun.value = current.copy(
                uncertain = true,
                reconnecting = false,
                pendingApproval = null
            )
        }
        serviceStarter.stop()
    }

    /**
     * Releases an UNCERTAIN run after an explicit reconciliation proved the
     * server run terminal. Strictly correlated: only the exact
     * [profileId]/[sessionId]/[runId] currently owned AND marked uncertain is
     * released; every mismatch is a no-op returning `false`, so a stale
     * reconciliation can never free a successor's slot.
     *
     * @return `true` when the uncertain run was released.
     */
    fun confirmTerminalForUncertainRun(
        profileId: String,
        sessionId: String,
        runId: String
    ): Boolean {
        synchronized(slotLock) {
            val current = _activeRun.value
            val matches = current != null &&
                current.uncertain &&
                current.profileId == profileId &&
                current.sessionId == sessionId &&
                current.runId == runId
            if (!matches) return false
            runJob?.cancel()
            runJob = null
            _activeRun.value = null
        }
        serviceStarter.stop()
        return true
    }

    /**
     * Clears ONE decided approval from the durable run state, so a
     * re-created screen cannot resurrect a request that is no longer
     * answerable (approval resurrection on re-attach).
     *
     * Strictly correlated: the record is only dropped when the slot
     * still belongs to this profile/session AND its pending approval is
     * exactly [runId]/[requestId]. A late result of an older run or an
     * older request therefore can never clear the current approval, and a
     * request that was already replaced stays untouched.
     *
     * Only authoritative terminal outcomes may be cleared through this:
     * an applied decision (`once`/`deny`) or the server's own "not pending"
     * answer. A transient/ambiguous transport failure is deliberately NOT
     * a reason to clear - the request stays resolvable after a reconnect.
     */
    fun clearPendingApproval(
        profileId: String,
        sessionId: String,
        runId: String,
        requestId: String
    ) {
        _activeRun.update { current ->
            val pending = current?.pendingApproval
            if (
                current != null &&
                current.profileId == profileId &&
                current.sessionId == sessionId &&
                pending != null &&
                pending.runId == runId &&
                pending.requestId == requestId
            ) {
                current.copy(pendingApproval = null)
            } else {
                current
            }
        }
    }

    /**
     * Tears the controller down (tests / process teardown): cancels the
     * active stream and stops the foreground service.
     */
    fun close() {
        scope.cancel()
        serviceStarter.stop()
        _activeRun.value = null
    }

    /**
     * Re-establishes a run that was confirmed before the process died
     * as UNCERTAIN (no transport, no foreground service, no POST). An
     * unreadable store counts as "no lease"; a lease whose profile is gone is
     * dropped because nothing could ever reconcile it.
     */
    private suspend fun restoreLease() {
        val lease = withContext(ioDispatcher) {
            val stored = try {
                leaseStore.load()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            stored?.takeIf {
                try {
                    isLeaseRestorable(it)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Fail closed: an unanswerable profile check keeps the
                    // ownership (the user can still reconcile it).
                    true
                }
            }
        }
        synchronized(slotLock) {
            if (lease != null && _activeRun.value == null) {
                _activeRun.value = ActiveRun(
                    profileId = lease.profileId,
                    sessionId = lease.sessionId,
                    runId = lease.runId,
                    uncertain = true
                )
            }
            leaseRestorePending = false
        }
    }

    /**
     * Mirrors the confirmed run identity of the slot into
     * [leaseStore]: a bound run ID writes the lease, a released slot clears
     * it. [close] cancels this collector before it empties the slot, so a
     * teardown (which is not an authoritative terminal) keeps the lease.
     */
    private suspend fun persistLeaseChanges() {
        _activeRun
            .map { run -> run?.runId?.let { RunLease(run.profileId, run.sessionId, it) } }
            .distinctUntilChanged()
            .collect { lease ->
                withContext(ioDispatcher) {
                    try {
                        if (lease == null) leaseStore.clear() else leaseStore.save(lease)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Best effort: a failed write only loses recovery
                        // after a later process death; the in-memory slot
                        // stays authoritative for this process.
                    }
                }
            }
    }

    private suspend fun handleEvent(
        profileId: String,
        sessionId: String,
        turnToken: Long,
        event: ChatTurnEvent
    ) {
        when (event) {
            is ChatTurnEvent.RunStarted -> updateActiveRun(sessionId, turnToken) {
                it.copy(runId = event.runId, reconnecting = false)
            }

            is ChatTurnEvent.AssistantStarted -> updateActiveRun(sessionId, turnToken) {
                it.copy(reconnecting = false)
            }

            is ChatTurnEvent.AssistantDelta -> updateActiveRun(sessionId, turnToken) {
                it.copy(assistantText = it.assistantText + event.text, reconnecting = false)
            }

            is ChatTurnEvent.AssistantCompleted -> updateActiveRun(sessionId, turnToken) {
                it.copy(assistantText = event.finalText, reconnecting = false)
            }

            is ChatTurnEvent.ToolActivity -> {
                val toolName = event.toolName
                if (toolName != null) {
                    updateActiveRun(sessionId, turnToken) {
                        it.copy(toolNames = it.toolNames + toolName, reconnecting = false)
                    }
                } else {
                    updateActiveRun(sessionId, turnToken) { it.copy(reconnecting = false) }
                }
            }

            is ChatTurnEvent.StreamReconnecting -> updateActiveRun(sessionId, turnToken) {
                it.copy(reconnecting = true)
            }

            is ChatTurnEvent.Reasoning -> updateActiveRun(sessionId, turnToken) {
                it.copy(reconnecting = false)
            }

            is ChatTurnEvent.ApprovalRequested -> {
                updateActiveRun(sessionId, turnToken) {
                    it.copy(pendingApproval = event.request, reconnecting = false)
                }
                // The title resolves the server label from profile metadata
                // (SharedPreferences + JSON), so keep it off the main thread.
                withContext(ioDispatcher) {
                    notificationCoordinator.onApprovalRequested(profileId, sessionId, event.request)
                }
            }

            is ChatTurnEvent.TurnCompleted -> {
                // The terminal path must honour the same turn identity as
                // every other update: a late terminal event of a run that
                // [startTurn] already replaced must never stop the successor's
                // foreground service, notify for its run id or clear its slot.
                if (slotTurnToken != turnToken) {
                    // A late terminal event of a run that [startTurn] already
                    // replaced must not reach observers: they are re-attached
                    // to the successor and cannot tell the two apart.
                    return
                }
                val runId = _activeRun.value?.runId
                // Stop the foreground service BEFORE the terminal is emitted
                // so a view model's reaction (the run-completed notification
                // is posted by the coordinator, not the view model) can never
                // race the foreground notification removal. The terminal
                // state itself clears the reconstructable turn state, so no
                // stale stop/reconnect/approval state can survive a
                // re-created view model.
                serviceStarter.stop()
                if (runId != null) {
                    // Server-label lookup is storage + JSON parsing; never on
                    // the main thread that emits the terminal event below.
                    withContext(ioDispatcher) {
                        notificationCoordinator.onRunCompleted(profileId, sessionId, runId)
                    }
                }
                _activeRun.value = null
            }

            is ChatTurnEvent.TurnFailed -> {
                if (slotTurnToken != turnToken) {
                    // Same rule as TurnCompleted: a replaced run's late
                    // terminal must not be observed by the successor.
                    return
                }
                // A failure only releases the single run slot when the
                // server PROVABLY ended the run (its own terminal error
                // frame) or when the start was provably rejected before a
                // run existed. A confirmed run plus a transport/protocol
                // failure is UNKNOWN: the run may still be alive, so its
                // ownership is kept (uncertain) and the foreground service
                // is stopped because there is no active transport.
                val runConfirmed = _activeRun.value?.runId != null
                when (RunTermination.ofFailure(event.error, runConfirmed)) {
                    RunTermination.TransportOutcomeUnknown -> {
                        serviceStarter.stop()
                        _activeRun.update { current ->
                            if (
                                current != null &&
                                current.sessionId == sessionId &&
                                slotTurnToken == turnToken
                            ) {
                                current.copy(
                                    uncertain = true,
                                    reconnecting = false,
                                    pendingApproval = null
                                )
                            } else {
                                current
                            }
                        }
                    }
                    RunTermination.AuthoritativeTerminal,
                    RunTermination.LocalRequestRejected -> {
                        serviceStarter.stop()
                        _activeRun.value = null
                    }
                }
            }
        }
        // Bookkeeping only: the event itself is still emitted to the live
        // observers exactly once.
        _events.emit(event)
    }

    /**
     * Applies [transform] to the active-run state, but only when the slot
     * still belongs to [sessionId] AND is still owned by the same turn
     * ([turnToken]): an event of another session - or a late event of a
     * run that [startTurn] already replaced - can never mutate this state,
     * and the transform is never applied to a cleared slot.
     */
    private inline fun updateActiveRun(
        sessionId: String,
        turnToken: Long,
        transform: (ActiveRun) -> ActiveRun
    ) {
        _activeRun.update { current ->
            if (
                current != null &&
                current.sessionId == sessionId &&
                slotTurnToken == turnToken
            ) {
                transform(current)
            } else {
                current
            }
        }
    }
}
