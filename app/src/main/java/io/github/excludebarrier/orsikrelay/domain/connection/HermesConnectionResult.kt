package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface HermesConnectionResult {
    /**
     * A successful health + capabilities probe.
     *
     * [sessionChatInteractiveApprovals] is `true` only when the probe
     * validates the complete versioned mobile session-chat approval
     * contract. Missing, partial, malformed, unknown-version or legacy-only
     * advertisements stay fail-closed.
     */
    data class Success(
        val version: String,
        val sessionsSupported: Boolean,
        val sessionStreamingSupported: Boolean,
        val sessionChatInteractiveApprovals: Boolean
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
