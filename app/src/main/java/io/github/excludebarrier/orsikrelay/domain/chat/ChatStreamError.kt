package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * Closed set of streaming chat turn failure reasons (Mission 006A).
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, server error messages, session IDs, message
 * content, exception text or credentials.
 */
sealed interface ChatStreamError {
    /** A locally invalid input (message, URL, session ID or credential). */
    data object InvalidInput : ChatStreamError

    /** The server rejected the bearer key (401/403). */
    data object InvalidCredentials : ChatStreamError

    /** The server reported the session does not exist (404). */
    data object SessionNotFound : ChatStreamError

    /** The server answered 409. No automatic retry is performed. */
    data object Conflict : ChatStreamError

    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : ChatStreamError

    /** The TLS handshake or certificate validation failed. */
    data object Tls : ChatStreamError

    /** The request timed out. */
    data object Timeout : ChatStreamError

    /** The network is unreachable or the stream was interrupted. */
    data object Network : ChatStreamError

    /** Any other unexpected HTTP status (including redirects) or a
     * server-side `error` event inside the stream. */
    data object Http : ChatStreamError

    /** The response is not the expected SSE/JSON contract: wrong content
     * type, invalid framing, size limits, wrong event order. */
    data object IncompatibleApi : ChatStreamError

    /**
     * The re-attach endpoint could not be used for the interrupted run
     * (unknown or foreign run, session mismatch, evicted buffer, or a
     * server without the re-attach endpoint). No retry is performed.
     */
    data object ResumeUnavailable : ChatStreamError

    /**
     * The server reports a replay gap on re-attach (409): the buffered
     * events no longer cover the point after the last confirmed
     * sequence. The run continues server-side; the turn fails closed
     * and is reconciled from run status/history. No retry is performed.
     */
    data object ResumeGap : ChatStreamError
}
