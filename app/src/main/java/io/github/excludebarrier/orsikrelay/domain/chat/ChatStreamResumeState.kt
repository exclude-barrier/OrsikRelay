package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * UI-safe snapshot of one streaming turn's sequence state (stream re-attach).
 *
 * Captured by the transport pipeline at the moment a stream dies, it is
 * the only state the client needs to re-attach to the same server run:
 * the confirmed [runId], the highest confirmed [lastSeq] (the
 * `after_seq` of the re-attach request) and the closed pipeline flags a
 * re-attaching pipeline must start with. It carries no secrets, no
 * URLs, no keys and no server payloads beyond the composed assistant
 * text the user already sees.
 */
data class ChatStreamResumeState(
    val runId: String,
    val lastSeq: Long,
    val messageStarted: Boolean,
    val messageId: String?,
    val composed: String,
    val assistantCompleted: Boolean,
    val finalText: String?,
    val runCompleted: Boolean,
    val sawError: Boolean
) {
    /**
     * `true` once the pipeline confirmed the terminal frame
     * (`run.completed`, `run.cancelled`, `run.failed` or `error`): a
     * re-attach cannot deliver a new terminal event, so there is nothing
     * left to re-attach for.
     */
    val terminal: Boolean
        get() = runCompleted || sawError
}
