package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.LostRun

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
    val localTool: Boolean = false,
    val imageCount: Int = 0
)

/**
 * Merges the loaded server history with the ephemeral local turn lines into
 * the single ordered timeline the screen renders. Server rows keep a
 * `server:<id>` key; local rows keep their own `local:<n>` key, so the two
 * sets never collide and the LazyColumn can key its items stably.
 */
internal fun buildTimeline(
    history: List<HermesChatMessage>,
    local: List<TimelineRow>
): List<TimelineRow> =
    history.map { message ->
        TimelineRow(
            key = "server:${message.id}",
            role = message.role,
            // SECURITY (history tool redaction): a tool message's `content`
            // is untrusted server data — commands, arguments, paths, URLs,
            // tool output, exit codes and approval payloads. The timeline
            // NEVER carries it for TOOL rows; the screen renders only the
            // safe role label derived from the validated `toolName`
            // metadata (fail closed, same policy as live tool activity).
            content = if (message.role == MessageRole.TOOL) {
                ""
            } else {
                message.content
            },
            toolName = message.toolName,
            imageCount = message.imageCount
        )
    } + local

/**
 * Immutable presentation state of the chat screen.
 *
 * This is the single, fully-observable state the screen renders from. Every
 * transition of the turn state machine is produced by [ChatViewModel] as a
 * new value of this type; the composable holds no business state of its own.
 *
 * The fields mirror the previous screen-local state one-to-one so the
 * verified baseline behavior (composer gating, run/stop/steer visibility,
 * recoverable steers, approval delivery) is preserved exactly. [boundRunId]
 * is the server-confirmed run ID of the active turn: it is used only to
 * gate the stop/steer controls and is never rendered, logged or embedded in
 * an error message.
 */
internal data class ChatUiState(
    val history: MessageHistoryState = MessageHistoryState.Loading,
    val loadInFlight: Boolean = false,
    /**
     * An older history page may exist and a user-triggered
     * [ChatViewModel.loadOlderMessages] is allowed. Cleared once the
     * server page is short, consists only of duplicates, or the history
     * is reloaded.
     */
    val canLoadOlder: Boolean = false,
    /** Exactly one older-page read is in flight. */
    val loadingOlder: Boolean = false,
    /**
     * Closed failure of the LAST older-page read. Deliberately separate
     * from the main history/turn failure so the already visible history
     * stays intact and the user can retry explicitly.
     */
    val olderLoadError: MessageHistoryError? = null,
    val draft: String = "",
    /**
     * The attachment the user picked but has not sent yet, or `null`.
     * Local-only: the picker's content URI never reaches the server, the
     * history or any log.
     */
    val attachment: ChatAttachmentDraft? = null,
    /** Closed rejection of the last picker result, or `null`. */
    val attachmentError: ChatAttachmentError? = null,
    /** `true` while the picked attachment is being read/validated. */
    val attachmentBusy: Boolean = false,
    val localLines: List<TimelineRow> = emptyList(),
    val turnFailure: ChatStreamError? = null,
    /**
     * The last send was rejected because another run already owns the
     * app-wide run slot. The draft and the picked attachment are kept; the
     * notice is generic and clears once the global run is terminal. The
     * waiting draft is never sent automatically.
     */
    val startConflict: Boolean = false,
    val turnActive: Boolean = false,
    /** The active turn's stream is being re-attached to the run. */
    val reconnecting: Boolean = false,
    /** The model of the active turn is reasoning (status only, no text). */
    val thinking: Boolean = false,
    /**
     * The active turn's server-side outcome is UNKNOWN (the transport or
     * the reliable event processing failed while the server run may still be
     * alive). The logical run ownership is kept; no new message is sent and
     * no automatic approval/stop is performed. The composer stays disabled
     * and a manual reconciliation is offered when a confirmed run ID exists.
     */
    val turnUncertain: Boolean = false,
    /** A manual reconciliation read of the uncertain run is in flight. */
    val reconcileInFlight: Boolean = false,
    /** The last reconciliation read failed with a closed, generic reason. */
    val reconcileFailed: Boolean = false,
    /** The last reconciliation proved the run still active server-side. */
    val reconcileStillActive: Boolean = false,
    /**
     * The last reconciliation found a run the core lost (status `unknown`):
     * the key the user's explicit "close as cancelled" sends back.
     */
    val lostRun: LostRun? = null,
    /** That acknowledgement is in flight. */
    val resolveInFlight: Boolean = false,
    /**
     * The core reports the run as lost but without the key to close it, so
     * nothing can be acknowledged from the app (and it is not "still active").
     */
    val lostRunWithoutKey: Boolean = false,
    val runStarted: Boolean = false,
    val boundRunId: String? = null,
    val pendingApproval: ApprovalRequest? = null,
    val stopInFlight: Boolean = false,
    val stopFailed: Boolean = false,
    val steerDraft: String = "",
    val steerInFlight: Boolean = false,
    val steerQueued: Boolean = false,
    val steerFailed: Boolean = false,
    val steerRecovery: List<String> = emptyList(),
    val steerSubmittedText: String? = null,
    val backPending: Boolean = false
) {
    /**
     * The stop and steer controls are visible only for an active turn whose
     * server-confirmed, non-blank run ID is bound: before
     * `run.started` the controls are invisible and no stop/steer POST is
     * possible.
     */
    val controlsVisible: Boolean
        get() = turnActive && boundRunId?.isNotBlank() == true && !turnUncertain

    /**
     * What the last timeline row says while a turn runs without visible
     * progress: [TurnActivity.Thinking] while the model reasons,
     * [TurnActivity.Tool] right after a live tool chip, otherwise
     * [TurnActivity.Working] until assistant text streams in. `null` while
     * text streams, an approval waits, the stream re-attaches, the outcome
     * is unknown, or no turn is active.
     */
    val turnActivity: TurnActivity?
        get() = when {
            !turnActive || turnUncertain || reconnecting || pendingApproval != null -> null
            thinking -> TurnActivity.Thinking
            localLines.lastOrNull()?.role == MessageRole.ASSISTANT -> null
            localLines.lastOrNull()?.let { it.role == MessageRole.TOOL && it.localTool } == true ->
                TurnActivity.Tool
            else -> TurnActivity.Working
        }

    /**
     * The run outcome is unknown and a server-confirmed run ID is bound,
     * so a manual status reconciliation is possible and safe.
     */
    val recoveryVisible: Boolean
        get() = turnUncertain && boundRunId?.isNotBlank() == true

    /**
     * The composer send action is enabled only when no turn is active, no
     * post-start failure is shown, and the draft is locally valid. A failure
     * BEFORE the confirmed start does not block a new, deliberate submit;
     * only an active turn or a failure after the confirmed start does.
     *
     * A picked attachment also enables the action (an attachment-only turn
     * is a legitimate request): the text may then be blank, and
     * [ChatOutboundMessage.create] decides the exact body.
     */
    val sendEnabled: Boolean
        get() = !turnActive &&
            !turnUncertain &&
            !attachmentBusy &&
            (turnFailure == null || !runStarted) &&
            ChatOutboundMessage.create(draft, pendingImageDataUrlOrNone) != null

    /** Placeholder used only for the local send-gate check; never transmitted. */
    private val pendingImageDataUrlOrNone: List<String>
        get() = if (attachment == null) emptyList() else listOf(ATTACHMENT_GATE_DATA_URL)

    private companion object {
        /** A well-formed placeholder so the gate can validate the text shape. */
        const val ATTACHMENT_GATE_DATA_URL = "data:image/png;base64,AA=="
    }
}

/** Progress shown at the end of the timeline, see [ChatUiState.turnActivity]. */
internal enum class TurnActivity { Working, Thinking, Tool }
