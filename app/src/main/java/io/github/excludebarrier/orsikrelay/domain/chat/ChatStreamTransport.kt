package io.github.excludebarrier.orsikrelay.domain.chat

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for
 * `POST /api/sessions/{session_id}/chat/stream`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesSessionStreamClient]. The
 * repository injects this seam so unit tests can verify URL, credential
 * and result propagation without a network. The key is only ever passed
 * as the scoped [SecretValue] argument and never appears in a result.
 *
 * Contract:
 * - [ChatStreamTransport.openTurn] must return quickly; the actual
 *   streaming happens on a transport-owned background thread;
 * - all [ChatStreamListener] callbacks may arrive on background threads;
 * - the transport never throws for protocol or network failures: every
 *   failure is delivered as exactly one
 *   [ChatTurnEvent.TurnFailed] terminal event;
 * - [ChatStreamHandle.close] must cancel the underlying call and must
 *   be idempotent; after close no further events are delivered.
 */
interface ChatStreamTransport {
    fun openTurn(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        message: String,
        interactiveApprovals: Boolean = false,
        listener: ChatStreamListener
    ): ChatStreamHandle

    /**
     * Opens one turn whose body may carry inline image attachments.
     *
     * The wire shape is the confirmed server contract: text-only stays the
     * plain `message` string, with images it becomes a
     * `text`/`image_url` content-part list carrying `data:image/...` URLs.
     * Nothing about the turn lifecycle, the listener contract or the
     * re-attach behaviour changes - an attachment travels only in the initial
     * POST body and is never repeated by a re-attach.
     *
     * The default implementation delegates to the text-only [openTurn], so a
     * transport without attachment support (or a test double) silently sends
     * the text alone instead of breaking the seam.
     */
    fun openTurnWithAttachments(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        message: ChatOutboundMessage,
        interactiveApprovals: Boolean = false,
        listener: ChatStreamListener
    ): ChatStreamHandle = openTurn(
        baseUrl = baseUrl,
        apiKey = apiKey,
        sessionId = sessionId,
        message = message.text,
        interactiveApprovals = interactiveApprovals,
        listener = listener
    )

    /**
     * Re-attaches to an already-started run of [sessionId]:
     * `GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N`,
     * where `N` is [resume].[ChatStreamResumeState.lastSeq]. The server
     * replays the buffered events with `seq > N`, then follows the run
     * live until its terminal tail.
     *
     * Replay/dedup contract (confirmed against the server,
     * `gateway/platforms/api_server.py`: `RunEventBuffer.next_event`
     * returns the next buffered event with `seq > after_seq`):
     * - the bound is STRICTLY exclusive, so no event with
     *   `seq <= N` is ever replayed and no already-processed event can
     *   reach the client twice;
     * - the client therefore does not need to persist its per-attempt
     *   `seenSeqs` map across a re-attach: the resumed pipeline starts
     *   at `N` and accepts exactly `N + 1` next. A frame that repeats
     *   `N` (or goes backwards) is not deduplicated but rejected
     *   closed - it would contradict the confirmed contract, and
     *   failing closed is the only safe answer without the identity of
     *   the previously confirmed frame;
     * - if the buffer no longer covers `N + 1` the server answers 409
     *   ([ChatStreamError.ResumeGap]) instead of silently skipping
     *   events;
     * - [resume].[ChatStreamResumeState.runId] MUST equal [runId]: a
     *   snapshot of any other run is rejected closed without a request.
     *
     * The [openTurn] contract applies unchanged: the method returns
     * quickly, all [ChatStreamListener] callbacks arrive on a
     * transport-owned background thread, the transport never throws for
     * protocol or network failures, and [ChatStreamHandle.close]
     * cancels the call and is idempotent.
     *
     * The default implementation fails closed with
     * [ChatStreamError.ResumeUnavailable]: a transport without re-attach
     * support simply cannot re-attach, and the repository settles the
     * turn with that closed reason.
     */
    fun openResume(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        runId: String,
        resume: ChatStreamResumeState,
        interactiveApprovals: Boolean = false,
        listener: ChatStreamListener
    ): ChatStreamHandle {
        try {
            listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.ResumeUnavailable))
        } finally {
            listener.onFinished()
        }
        return object : ChatStreamHandle {
            override fun close() {
                // nothing to cancel
            }
        }
    }
}

/** Callback sink of one streaming turn. */
interface ChatStreamListener {
    /** Delivers one closed, UI-safe event of the turn. */
    fun onEvent(event: ChatTurnEvent)

    /** Delivered exactly once when the turn finished for any reason. */
    fun onFinished()
}

/** Handle of one open stream; closing it cancels the underlying call. */
interface ChatStreamHandle {
    fun close()

    /**
     * UI-safe snapshot of the stream's confirmed sequence state,
     * or `null` while no run is confirmed yet or the transport does
     * not track one. Read only after the stream has finished (the
     * terminal event has been delivered).
     */
    fun resumeState(): ChatStreamResumeState? = null
}
