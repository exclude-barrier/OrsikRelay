package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed server run status read from `GET /v1/runs/{run_id}`.
 *
 * The set is the closed status vocabulary derived read-only from the
 * installed server code (`gateway/platforms/api_server_runs.py`,
 * `_set_run_status` and `TERMINAL_STATUSES` in
 * `api_server_run_idempotency.py`). An unknown status string is not a
 * value here: the transport fails closed with
 * [RunStatusError.IncompatibleApi] instead of inventing a state.
 */
enum class RunServerStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_APPROVAL,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    /**
     * A terminal run can never become active again, so the ownership may
     * be released authoritatively.
     */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}

/** Closed set of run-status read failure reasons. */
sealed interface RunStatusError {
    /** A locally invalid input (URL, credential or run ID) was rejected. */
    data object InvalidInput : RunStatusError

    /** The server rejected the bearer key (HTTP 401/403). */
    data object InvalidCredentials : RunStatusError

    /** The server answered 404: the run is unknown, foreign or expired. */
    data object NotFound : RunStatusError

    /** The server answered 409. No automatic retry is performed. */
    data object Conflict : RunStatusError

    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : RunStatusError

    /** The TLS handshake or certificate validation failed. */
    data object Tls : RunStatusError

    /** The request timed out. */
    data object Timeout : RunStatusError

    /** The network is unreachable or the connection failed. */
    data object Network : RunStatusError

    /** Any other unexpected HTTP status (including redirects). */
    data object Http : RunStatusError

    /** The response is not the expected `GET /v1/runs/{id}` contract. */
    data object IncompatibleApi : RunStatusError
}

/**
 * Closed result of one run-status read. Every failure maps to a
 * closed [RunStatusError]; no response body, URL, run ID or exception
 * text ever escapes into the result.
 */
sealed interface RunStatusResult {
    /** The server returned the pollable status of exactly the requested run. */
    data class Known(val status: RunServerStatus) : RunStatusResult

    /** The status read failed; [error] is closed and generic. */
    data class Failure(val error: RunStatusError) : RunStatusResult
}
