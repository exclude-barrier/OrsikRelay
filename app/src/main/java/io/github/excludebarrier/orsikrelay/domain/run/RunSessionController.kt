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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    /**
     * The single active run. [runId] is bound only after the server
     * confirmed the run (`RunStarted`); [pendingApproval] is the request
     * the user has not decided yet (restored when a view model re-attaches
     * to a running session).
     */
    data class ActiveRun(
        val profileId: String,
        val sessionId: String,
        val runId: String? = null,
        val pendingApproval: ApprovalRequest? = null
    )

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

    private var runJob: Job? = null

    /**
     * Starts one streaming turn for [message] in [sessionId] (single slot:
     * a still-active run is terminated first, exactly like leaving its
     * screen used to do). The foreground service is started synchronously
     * before the stream is opened — the caller is the user's send tap, so
     * the app is in the foreground and the Android 14+ background-start
     * restriction cannot apply.
     */
    fun startTurn(profileId: String, sessionId: String, message: String) {
        val previous = runJob
        if (previous != null && previous.isActive) {
            previous.cancel()
        }
        // Optimistic start: a send that fails immediately stops the service
        // again on the terminal event (below), so a failed turn leaves no
        // background footprint.
        serviceStarter.start(profileId, sessionId)
        _activeRun.value = ActiveRun(profileId = profileId, sessionId = sessionId)
        val job = scope.launch {
            var terminalSeen = false
            try {
                chatSource.streamTurn(profileId, sessionId, message).collect { event ->
                    if (event is ChatTurnEvent.TurnCompleted ||
                        event is ChatTurnEvent.TurnFailed
                    ) {
                        terminalSeen = true
                    }
                    handleEvent(profileId, sessionId, event)
                }
                // Defensive fail-closed: the source contract guarantees a
                // terminal event, but a stream that closes without one must
                // still settle as a generic network failure.
                if (!terminalSeen) {
                    handleEvent(profileId, sessionId, ChatTurnEvent.TurnFailed(ChatStreamError.Network))
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
                handleEvent(profileId, sessionId, ChatTurnEvent.TurnFailed(ChatStreamError.Network))
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
     * Clears a decided approval (the view model calls this after the user's
     * decision has been POSTed), so a re-attached view model does not
     * re-show a request the user already resolved.
     */
    fun clearPendingApproval() {
        _activeRun.update { current ->
            current?.let { it.copy(pendingApproval = null) }
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

    private suspend fun handleEvent(profileId: String, sessionId: String, event: ChatTurnEvent) {
        when (event) {
            is ChatTurnEvent.RunStarted -> _activeRun.update { current ->
                if (current != null && current.sessionId == sessionId) {
                    current.copy(runId = event.runId)
                } else {
                    current
                }
            }

            is ChatTurnEvent.ApprovalRequested -> {
                _activeRun.update { current ->
                    if (current != null && current.sessionId == sessionId) {
                        current.copy(pendingApproval = event.request)
                    } else {
                        current
                    }
                }
                notificationCoordinator.onApprovalRequested(profileId, sessionId, event.request)
            }

is ChatTurnEvent.TurnCompleted -> {
                val runId = _activeRun.value?.runId
                // Stop the foreground service BEFORE the terminal is emitted
                // so a view model's reaction (the run-completed notification
                // is posted by the coordinator, not the view model) can never
                // race the foreground notification removal.
                serviceStarter.stop()
                if (runId != null) {
                    notificationCoordinator.onRunCompleted(profileId, sessionId, runId)
                }
                _activeRun.value = null
            }

            is ChatTurnEvent.TurnFailed -> {
                serviceStarter.stop()
                _activeRun.value = null
            }

            is ChatTurnEvent.AssistantStarted,
            is ChatTurnEvent.AssistantDelta,
            is ChatTurnEvent.AssistantCompleted,
            is ChatTurnEvent.ToolActivity -> Unit
        }
_events.emit(event)
    }
}
