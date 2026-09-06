package io.github.excludebarrier.orsikrelay.domain.chat

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
    /** The server confirmed the run start; the sent message is accepted. */
    data object RunStarted : ChatTurnEvent

    /** The assistant started exactly one message for this turn. */
    data object AssistantStarted : ChatTurnEvent

    /** One validated text fragment of the current assistant message. */
    data class AssistantDelta(val text: String) : ChatTurnEvent

    /** The authoritative final assistant text of this turn. */
    data class AssistantCompleted(val finalText: String) : ChatTurnEvent

    /** Generic tool activity; never includes tool name, args or preview. */
    data object ToolActivity : ChatTurnEvent

    /** The run completed successfully. */
    data object TurnCompleted : ChatTurnEvent

    /** Closed generic failure; nothing is retried automatically. */
    data class TurnFailed(val error: ChatStreamError) : ChatTurnEvent
}
