package io.github.excludebarrier.orsikrelay.domain.terminal

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * The bridge's interactive terminal (`GET /v1/terminal`, a WebSocket on a
 * tmux session). Off unless the server operator enabled it; it is a shell
 * on the server and bypasses Hermes approvals.
 */
sealed interface TerminalEvent {
    /** Raw PTY output (UTF-8 and escape sequences, possibly split anywhere). */
    class Output(val bytes: ByteArray) : TerminalEvent

    /** Delivered exactly once; nothing follows it. */
    data class Closed(val reason: TerminalCloseReason) : TerminalEvent
}

enum class TerminalCloseReason {
    /** The shell (tmux client) ended. */
    Exited,

    /** The device credential is no longer valid. */
    Unauthorized,

    /** The server has the terminal switched off. */
    Disabled,

    /** The server's terminal limit is reached. */
    Busy,

    /** The connection broke or the server failed. */
    Disconnected
}

/** An open terminal. Calls after [close] (or a [TerminalEvent.Closed]) are ignored. */
interface TerminalConnection {
    fun send(bytes: ByteArray): Boolean
    fun resize(columns: Int, rows: Int): Boolean
    fun close()
}

/** Authenticated transport; production: `HermesTerminalClient`. */
interface TerminalTransport {
    fun supports(baseUrl: String, apiKey: SecretValue): Boolean

    /** Never retried: opening a shell is not an idempotent read. */
    fun open(baseUrl: String, apiKey: SecretValue, onEvent: (TerminalEvent) -> Unit): TerminalConnection?
}

sealed interface TerminalAvailability {
    data object Available : TerminalAvailability
    data object Unsupported : TerminalAvailability
    data object Failed : TerminalAvailability
}

/** UI seam; both calls block, so run them on `Dispatchers.IO`. */
interface TerminalSource {
    fun availability(profileId: String): TerminalAvailability
    fun open(profileId: String, onEvent: (TerminalEvent) -> Unit): TerminalConnection?
}
