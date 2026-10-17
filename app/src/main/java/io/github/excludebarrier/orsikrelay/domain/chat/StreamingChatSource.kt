package io.github.excludebarrier.orsikrelay.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Small streaming seam for one text chat turn.
 *
 * The UI depends only on this interface and the closed [ChatTurnEvent]
 * set. The production implementation is the repository in the `data`
 * package, which loads profile metadata and credential separately for
 * the given profile ID and delegates the authenticated stream to the
 * network transport. Compose tests inject synthetic fakes of this
 * interface.
 *
 * Implementations must be safe to collect on the main dispatcher.
 * Cancellation of the collector must close the underlying transport
 * call. The flow always terminates with exactly one terminal event:
 * [ChatTurnEvent.TurnCompleted] or [ChatTurnEvent.TurnFailed]. No
 * automatic retry is ever performed.
 */
fun interface StreamingChatSource {
    fun streamTurn(
        profileId: String,
        sessionId: String,
        message: String
    ): Flow<ChatTurnEvent>

    /**
     * Streams one turn whose body may carry inline image attachments.
     *
     * Additive seam: the default implementation streams the text alone, so
     * every existing source (and every test double) keeps its exact
     * behaviour, and an attachment can never be silently invented by a source
     * that does not implement it.
     */
    fun streamTurnWithAttachments(
        profileId: String,
        sessionId: String,
        message: String,
        imageDataUrls: List<String>
    ): Flow<ChatTurnEvent> = streamTurn(profileId, sessionId, message)
}
