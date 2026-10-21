package io.github.excludebarrier.orsikrelay.domain.message

/**
 * One validated, displayable chat message from
 * `GET /api/sessions/{session_id}/messages`.
 *
 * Only sanitized display fields are kept. `reasoning`,
 * `reasoning_content`, raw `tool_calls`, internal compression data and
 * any other unknown server field are neither parsed into this state nor
 * logged.
 */
data class HermesChatMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val timestampSeconds: Long?,
    val toolName: String?,
    /** Images attached to the message; their data is never kept or shown. */
    val imageCount: Int = 0
)

/** Closed set of message roles the UI can distinguish. */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}
