package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError

/**
 * Closed classification of how a streaming turn ended, used by the
 * app-level run owner to decide whether the single run slot may be
 * released (P7).
 *
 * A Hermes run is independent of its SSE socket server-side: once the
 * server confirmed a run (`run.started`), a local transport, timeout,
 * TLS or protocol failure does NOT prove the server run is terminal.
 * Only [AuthoritativeTerminal] may therefore release the run ownership.
 */
sealed interface RunTermination {
    /**
     * The server provably ended the run: `run.completed` (success) or
     * the server's own terminal `error` frame ([ChatStreamError.ServerError]).
     * The run slot, the foreground service and the UI may settle.
     */
    data object AuthoritativeTerminal : RunTermination

    /**
     * The client lost the transport or the reliable event processing
     * while a run may still be running server-side. The run ownership
     * MUST NOT be released silently: no new POST, no implicit server
     * stop, only an explicit reconciliation. This also covers a failed
     * initial POST without a confirmed run ID, because that POST may
     * have been accepted server-side.
     */
    data object TransportOutcomeUnknown : RunTermination

    /**
     * The start was provably rejected before a run existed: a locally
     * invalid input, or a non-2xx HTTP answer (which the server returns
     * before it creates the run). The slot may be released.
     */
    data object LocalRequestRejected : RunTermination

    companion object {
        /**
         * Classifies a failed turn. [runConfirmed] is `true` once the
         * server bound the run ID of THIS turn (`run.started` seen).
         *
         * The order matters: a server terminal `error` frame is always
         * authoritative; a confirmed run turns every other failure into
         * [TransportOutcomeUnknown]; only a proven pre-run rejection of
         * an UNCONFIRMED start is [LocalRequestRejected].
         */
        fun ofFailure(error: ChatStreamError, runConfirmed: Boolean): RunTermination =
            when {
                error == ChatStreamError.ServerError -> AuthoritativeTerminal
                runConfirmed -> TransportOutcomeUnknown
                error == ChatStreamError.InvalidInput ||
                    error == ChatStreamError.InvalidCredentials ||
                    error == ChatStreamError.SessionNotFound ||
                    error == ChatStreamError.Conflict ||
                    error == ChatStreamError.RateLimited ||
                    error == ChatStreamError.Http -> LocalRequestRejected
                // Network / Timeout / Tls / IncompatibleApi / ResumeUnavailable /
                // ResumeGap without a confirmed run: the POST may have been
                // accepted before the failure, so the outcome is unknown.
                else -> TransportOutcomeUnknown
            }
    }
}
