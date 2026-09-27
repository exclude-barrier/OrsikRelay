package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest

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
            toolName = message.toolName
        )
    } + local

/**
 * Immutable presentation state of the chat screen (P1 refactor).
 *
 * This is the single, fully-observable state the screen renders from. Every
 * transition of the turn state machine is produced by [ChatViewModel] as a
 * new value of this type; the composable holds no business state of its own.
 *
 * The fields mirror the previous screen-local state one-to-one so the
 * verified P0 behavior (composer gating, run/stop/steer visibility,
 * recoverable steers, approval delivery) is preserved exactly. [boundRunId]
 * is the server-confirmed run ID of the active turn: it is used only to
 * gate the stop/steer controls and is never rendered, logged or embedded in
 * an error message.
 */
internal data class ChatUiState(
    val history: MessageHistoryState = MessageHistoryState.Loading,
    val loadInFlight: Boolean = false,
    val draft: String = "",
    /**
     * P5: the attachment the user picked but has not sent yet, or `null`.
     * Local-only: the picker's content URI never reaches the server, the
     * history or any log.
     */
    val attachment: ChatAttachmentDraft? = null,
    /** P5: closed rejection of the last picker result, or `null`. */
    val attachmentError: ChatAttachmentError? = null,
    /** P5: `true` while the picked attachment is being read/validated. */
    val attachmentBusy: Boolean = false,
    val localLines: List<TimelineRow> = emptyList(),
    val turnFailure: ChatStreamError? = null,
    val turnActive: Boolean = false,
    /** P4: the active turn's stream is being re-attached to the run. */
    val reconnecting: Boolean = false,
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
     * server-confirmed, non-blank run ID is bound (Mission 007B/008B): before
     * `run.started` the controls are invisible and no stop/steer POST is
     * possible.
     */
    val controlsVisible: Boolean
        get() = turnActive && boundRunId?.isNotBlank() == true

    /**
     * The composer send action is enabled only when no turn is active, no
     * post-start failure is shown, and the draft is locally valid. A failure
     * BEFORE the confirmed start does not block a new, deliberate submit;
     * only an active turn or a failure after the confirmed start does.
     *
     * P5: a picked attachment also enables the action (an attachment-only turn
     * is a legitimate request): the text may then be blank, and
     * [ChatOutboundMessage.create] decides the exact body.
     */
    val sendEnabled: Boolean
        get() = !turnActive &&
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
