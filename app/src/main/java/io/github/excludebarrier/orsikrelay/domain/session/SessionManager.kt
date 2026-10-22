package io.github.excludebarrier.orsikrelay.domain.session

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * UI seam for managing one chat from the session list: archive (and undo)
 * and delete. The actions are offered only when [supportsManagement] is
 * `true`, i.e. the server's `/v1/capabilities` advertises both.
 *
 * Implementations run on `Dispatchers.IO`, never retry a write and never
 * leak the key, a response body or exception text into a result.
 */
interface SessionManager {
    /** Idempotent capability read; any failure answers `false`. */
    fun supportsManagement(profileId: String): Boolean
    fun setArchived(profileId: String, sessionId: String, archived: Boolean): SessionManageResult
    fun delete(profileId: String, sessionId: String): SessionManageResult
}

/** Authenticated transport behind [SessionManager]; production: `HermesSessionManageClient`. */
interface SessionManageTransport {
    fun supportsManagement(baseUrl: String, apiKey: SecretValue): Boolean
    fun setArchived(baseUrl: String, apiKey: SecretValue, sessionId: String, archived: Boolean): SessionManageResult
    fun delete(baseUrl: String, apiKey: SecretValue, sessionId: String): SessionManageResult
}

sealed interface SessionManageResult {
    data object Success : SessionManageResult
    data class Failure(val error: SessionManageError) : SessionManageResult
}

enum class SessionManageError {
    /** The chat has a running turn (core 409); stop it first. */
    ActiveTurn,

    /** The server confirms the chat no longer exists. */
    NotFound,

    /** Anything else (network, TLS, auth, unexpected answer). */
    Failed
}
