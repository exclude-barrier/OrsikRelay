package io.github.excludebarrier.orsikrelay.domain.message

/**
 * Closed set of chat-history failure reasons.
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, session IDs, message content, exception text,
 * profile data or credentials.
 */
sealed interface MessageHistoryError {
    /** No profile exists or its metadata/credential is incomplete. */
    data object MissingProfile : MessageHistoryError
    /** The requested session ID or stored profile URL is invalid. */
    data object InvalidInput : MessageHistoryError
    /** The server rejected the bearer key (401/403). */
    data object InvalidCredentials : MessageHistoryError
    /** The server reported the session does not exist (404). */
    data object SessionNotFound : MessageHistoryError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : MessageHistoryError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : MessageHistoryError
    /** The request timed out. */
    data object Timeout : MessageHistoryError
    /** The network is unreachable or the connection failed. */
    data object Network : MessageHistoryError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : MessageHistoryError
    /** The response is not the expected messages contract. */
    data object IncompatibleApi : MessageHistoryError
}
