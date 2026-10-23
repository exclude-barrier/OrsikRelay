package io.github.excludebarrier.orsikrelay.ui.terminal

import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface TerminalUiState {
    data object Checking : TerminalUiState
    data object Unsupported : TerminalUiState
    data object CheckFailed : TerminalUiState

    /** Available; the device credential must confirm before a shell opens. */
    data object NeedsUnlock : TerminalUiState
    data object UnlockUnavailable : TerminalUiState
    data object UnlockFailed : TerminalUiState
    data object Connected : TerminalUiState
    data class Closed(val reason: TerminalCloseReason) : TerminalUiState

    /** Left in the background; asks again on return. */
    data object Paused : TerminalUiState
}

/**
 * One terminal screen visit: availability, the unlock gate and at most one
 * connection. Opening is never automatic after a failure (a shell is not an
 * idempotent read); the user taps "Neu verbinden". Main-thread confined,
 * except the transport callback, which only hands bytes to a bounded
 * channel: a full channel blocks the socket reader, so a flood throttles
 * the server instead of filling memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TerminalController(
    private val source: TerminalSource,
    private val profileId: String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<TerminalUiState>(TerminalUiState.Checking)
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private val outputChannel = MutableStateFlow<Channel<ByteArray>?>(null)

    /** The current connection's output, in order; empty between connections. */
    val output: Flow<ByteArray> = outputChannel.flatMapLatest { it?.receiveAsFlow() ?: emptyFlow() }

    private var connection: TerminalConnection? = null
    private var size: Pair<Int, Int>? = null

    @Volatile
    private var generation = 0

    fun check() {
        val visit = ++generation
        _state.value = TerminalUiState.Checking
        scope.launch {
            val availability = withContext(ioDispatcher) { source.availability(profileId) }
            if (visit != generation) return@launch
            _state.value = when (availability) {
                TerminalAvailability.Available -> TerminalUiState.NeedsUnlock
                TerminalAvailability.Unsupported -> TerminalUiState.Unsupported
                TerminalAvailability.Failed -> TerminalUiState.CheckFailed
            }
        }
    }

    fun unlockUnavailable() {
        if (_state.value == TerminalUiState.NeedsUnlock) _state.value = TerminalUiState.UnlockUnavailable
    }

    fun unlockFailed() {
        if (_state.value == TerminalUiState.NeedsUnlock) _state.value = TerminalUiState.UnlockFailed
    }

    /** After a successful unlock or the user's "Neu verbinden". */
    fun connect() {
        disconnect()
        val visit = ++generation
        val channel = Channel<ByteArray>(OUTPUT_CHUNKS)
        outputChannel.value = channel
        _state.value = TerminalUiState.Connected
        scope.launch {
            val opened = withContext(ioDispatcher) {
                source.open(profileId) { event -> onEvent(visit, channel, event) }
            }
            if (visit != generation) {
                opened?.close()
                return@launch
            }
            if (opened == null) {
                finish(visit, channel, TerminalCloseReason.Disconnected)
                return@launch
            }
            // A Closed may already have been handled while open() returned.
            if (_state.value != TerminalUiState.Connected) {
                opened.close()
                return@launch
            }
            connection = opened
            size?.let { (columns, rows) -> opened.resize(columns, rows) }
        }
    }

    /** A long paste goes out in pieces below the bridge's per-message bound. */
    fun input(bytes: ByteArray) {
        val open = connection ?: return
        var start = 0
        while (start < bytes.size) {
            val end = minOf(bytes.size, start + INPUT_CHUNK_BYTES)
            if (!open.send(bytes.copyOfRange(start, end))) return
            start = end
        }
    }

    fun resize(columns: Int, rows: Int) {
        size = columns to rows
        connection?.resize(columns, rows)
    }

    /** ON_STOP (not a configuration change): the shell closes, tmux keeps it. */
    fun pause() {
        disconnect()
        ++generation
        _state.value = TerminalUiState.Paused
    }

    fun dispose() {
        disconnect()
        ++generation
    }

    private fun disconnect() {
        connection?.close()
        connection = null
        outputChannel.value?.close()
        outputChannel.value = null
    }

    private fun onEvent(visit: Int, channel: Channel<ByteArray>, event: TerminalEvent) {
        if (visit != generation) return
        when (event) {
            // Blocks the socket reader while the page is behind; returns at
            // once when the channel was closed (the visit ended).
            is TerminalEvent.Output -> channel.trySendBlocking(event.bytes)
            is TerminalEvent.Closed -> scope.launch { finish(visit, channel, event.reason) }
        }
    }

    private fun finish(visit: Int, channel: Channel<ByteArray>, reason: TerminalCloseReason) {
        if (visit != generation) return
        connection = null
        channel.close()
        _state.value = TerminalUiState.Closed(reason)
    }

    internal companion object {
        const val OUTPUT_CHUNKS = 64
        const val INPUT_CHUNK_BYTES = 16 * 1024
    }
}
