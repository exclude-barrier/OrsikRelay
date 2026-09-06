package io.github.excludebarrier.orsikrelay.domain.chat

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for
 * `POST /api/sessions/{session_id}/chat/stream` (Mission 006A).
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
        listener: ChatStreamListener
    ): ChatStreamHandle
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
}
