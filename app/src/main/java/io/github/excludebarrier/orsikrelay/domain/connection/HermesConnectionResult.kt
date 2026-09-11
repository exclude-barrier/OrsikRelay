package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface HermesConnectionResult {
    /**
     * A successful health + capabilities probe.
     *
     * [runApprovalResponse] (Mission 014) carries the server's
     * `features.run_approval_response` capability flag, read fail-closed
     * from the capabilities response: an absent or non-boolean value is
     * `false`, so the approval UI can never be enabled for a server that
     * does not affirmatively advertise the capability.
     */
    data class Success(
        val version: String,
        val sessionsSupported: Boolean,
        val sessionStreamingSupported: Boolean,
        val runApprovalResponse: Boolean
    ) : HermesConnectionResult

    data class Failure(
        val reason: ConnectionFailureReason
    ) : HermesConnectionResult
}

enum class ConnectionFailureReason {
    INVALID_INPUT,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    INCOMPATIBLE_API,
    TLS,
    TIMEOUT,
    NETWORK,
    HTTP
}
