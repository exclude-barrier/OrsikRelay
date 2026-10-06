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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seam for starting and stopping the run foreground service (P3).
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
 * App-level owner of the single active agent run (P3 background continuity).
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
 * - The service is stopped as soon as the run is terminal:
 *   `run.completed`, `run.failed`, a closed stream, a user stop that the
 *   server confirms, or a definitive transport/auth failure — all of them
 *   surface as the stream's single terminal event.
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
    private val ioDispatcher: CoroutineDispatcher = dispatcher
) {
    /**
     * The single active run: the small, closed in-memory state a chat
     * screen needs to re-attach to a running turn (P4 recreation fix).
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
     * Nothing here is persisted (no disk, no process-death recovery), and no
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
        val pendingApproval: ApprovalRequest? = null
    ) {
        /** Closed, derived status of the active run. */
        val status: Status
            get() = when {
                pendingApproval != null -> Status.WAITING_FOR_APPROVAL
                reconnecting -> Status.RECONNECTING
                else -> Status.RUNNING
            }

        /** Closed set of active-run statuses for the UI projection. */
        enum class Status { RUNNING, RECONNECTING, WAITING_FOR_APPROVAL }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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

    // Visible across the (single-threaded) dispatcher and any thread that
    // calls startTurn/cancelTurn; the controller processes events serially, so
    // the compound token update below is not concurrent in practice.
    @Volatile
    private var runJob: Job? = null

    /**
     * Identity of the turn currently owning the slot (P4). A replaced run's
     * collector may still execute its synchronous body after
     * [startTurn] cancelled it; the token makes such a late event a no-op
     * instead of letting it write the previous run's text into the new
     * slot.
     */
    @Volatile
    private var slotTurnToken: Long = 0L

    /**
     * Starts one streaming turn for [message] in [sessionId] (single slot:
     * a still-active run is terminated first, exactly like leaving its
     * screen used to do). The foreground service is started synchronously
     * before the stream is opened — the caller is the user's send tap, so
     * the app is in the foreground and the Android 14+ background-start
     * restriction cannot apply.
     */
    fun startTurn(
        profileId: String,
        sessionId: String,
        message: String,
        /**
         * P5: inline image data URLs of this turn's attachments. They travel
         * only in the initial POST body - never into the durable run state
         * (they are large and carry user content the UI already shows) and
         * never into a P4 re-attach.
         */
        imageDataUrls: List<String> = emptyList()
    ) {
        val previous = runJob
        if (previous != null && previous.isActive) {
            previous.cancel()
        }
        // Optimistic start: a send that fails immediately stops the service
        // again on the terminal event (below), so a failed turn leaves no
        // background footprint. The validated user text is kept in the
        // active-run state (P4), so a re-created view model can restore the
        // visible turn without a new POST.
        val token = slotTurnToken + 1L
        slotTurnToken = token
        serviceStarter.start(profileId, sessionId)
        _activeRun.value = ActiveRun(
            profileId = profileId,
            sessionId = sessionId,
            userMessage = message
        )
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
                // Single-slot replacement, [cancelTurn] or [close]: no
                // terminal is emitted, and the foreground service is NOT
                // stopped here — a replacement re-asserts it via [start],
                // and the cancel/teardown callers stop it themselves.
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
                // Bookkeeping only: stop ownership lives in the terminal
                // handlers ([handleEvent]) and in [cancelTurn]/[close].
                // Guarded, so a late finally of a replaced run cannot clear
                // the slot of its successor.
                if (runJob === currentCoroutineContext()[Job]) {
                    _activeRun.value = null
                }
            }
        }
        runJob = job
    }

    /**
     * Cancels the active stream: the underlying transport call is closed and
     * the server interrupts the interrupted run. No terminal event is
     * emitted for a canceled stream; the foreground service is stopped and
     * the single slot cleared immediately.
     */
    fun cancelTurn() {
        val job = runJob
        runJob = null
        job?.cancel()
        serviceStarter.stop()
        _activeRun.value = null
    }

    /**
     * Clears ONE decided approval from the durable run state, so a
     * re-created screen cannot resurrect a request that is no longer
     * answerable (P4-APPROVAL-RESURRECTION).
     *
     * Strictly correlated (P4): the record is only dropped when the slot
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
                serviceStarter.stop()
                _activeRun.value = null
            }
        }
        // Bookkeeping only: the event itself is still emitted to the live
        // observers exactly once.
        _events.emit(event)
    }

    /**
     * Applies [transform] to the active-run state, but only when the slot
     * still belongs to [sessionId] AND is still owned by the same turn
     * ([turnToken], P4): an event of another session - or a late event of a
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
