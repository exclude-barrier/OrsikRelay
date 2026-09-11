package io.github.excludebarrier.orsikrelay.domain.chat

import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest

/**
 * Closed set of lifecycle-safe events of one streaming chat turn
 * (Mission 006A).
 *
 * Every event carries only closed, UI-safe data: the composed assistant
 * text, the final assistant text, and closed error codes. Server fields
 * such as `user_message`, `runtime`, `usage`, tool names, arguments,
 * previews or reasoning content are never propagated.
 *
 * A turn flow always terminates with exactly one terminal event:
 * [TurnCompleted] on success or [TurnFailed] on failure.
 */
sealed interface ChatTurnEvent {
    /**
     * The server confirmed the run start; [runId] is the validated ID
     * required by later control requests such as stop.
     */
    data class RunStarted(val runId: String) : ChatTurnEvent

    /** The assistant started exactly one message for this turn. */
    data object AssistantStarted : ChatTurnEvent

    /** One validated text fragment of the current assistant message. */
    data class AssistantDelta(val text: String) : ChatTurnEvent

    /** The authoritative final assistant text of this turn. */
    data class AssistantCompleted(val finalText: String) : ChatTurnEvent

    /** Generic tool activity; never includes tool name, args or preview. */
    data object ToolActivity : ChatTurnEvent

    /**
     * One server-redacted, exact pending approval for this active turn.
     * The request is resolved only by the existing once/deny requester.
     */
    data class ApprovalRequested(val request: ApprovalRequest) : ChatTurnEvent

    /**
     * The run completed successfully. [pendingSteer] carries the server's
     * `pending_steer` field of `run.completed`, preserved exactly (Mission
     * 008B): a steer that was queued but not yet applied by the run comes
     * back here. It is `null` when the field was absent, blank, or not a
     * string. The UI must only offer to send it as a NEW message — never
     * automatically — and it is not a steer request itself.
     */
    data class TurnCompleted(val pendingSteer: String? = null) : ChatTurnEvent

    /** Closed generic failure; nothing is retried automatically. */
    data class TurnFailed(val error: ChatStreamError) : ChatTurnEvent
}
