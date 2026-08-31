package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface HermesConnectionResult {
    data class Success(
        val version: String,
        val sessionsSupported: Boolean,
        val sessionStreamingSupported: Boolean
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
