package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamResumeState
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamTransport
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalCapabilitySource
import io.github.excludebarrier.orsikrelay.network.HermesSessionStreamClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Production [StreamingChatSource] (Mission 006A).
 *
 * Loads the profile metadata and the credential separately for the
 * given profile ID and then delegates the authenticated stream to
 * [ChatStreamTransport] (production: [HermesSessionStreamClient]).
 *
 * The turn flow is built on [callbackFlow] and uses the producer scope
 * directly: each transport attempt's background thread forwards
 * non-terminal events with `trySendBlocking`, so a full default channel
 * buffer applies bounded backpressure to the transport instead of
 * dropping a valid stream, and a blocked or failed send closes the
 * scope as soon as the collector is gone. The terminal event of an
 * attempt is buffered by the listener and handed to the producer
 * through a per-attempt deferred (the happens-before edge), so the
 * producer — never the transport thread — decides whether the turn
 * ends or re-attaches (P4). Cancellation at any suspension point
 * (awaiting an attempt, re-attach backoff) closes the scope, and the
 * `finally` closes the current transport handle exactly once, so the
 * stream never outlives the collector.
 *
 * Every failure maps to a closed [ChatStreamError] reason: no profile
 * data, URL, key, session ID or exception text ever escapes into the
 * flow. An incomplete or missing profile (metadata, credential or an
 * empty key) is reported as [ChatStreamError.InvalidInput] — the closed
 * streaming error set has no profile-specific variant.
 *
 * The whole flow runs on [Dispatchers.IO] via [flowOn], so the profile
 * and credential reads (SharedPreferences and Keystore) and the stream
 * never execute on the collecting (main) thread. The turn is
 * never re-POSTed; after a transient stream failure only the socket is
 * re-attached to the same server run (P4), bounded by
 * [maxReconnectAttempts] with [reconnectBackoffMs] between attempts. A
 * re-attach attempt that fails before its stream was bound (an HTTP
 * status failure such as a transient 5xx, or a synchronous open
 * failure) processed no frame: the loop then continues from the
 * confirmed state the attempt was started from, so the run and its
 * `after_seq` survive the hiccup instead of ending the turn.
 */
class StreamingChatRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val streamTransport: ChatStreamTransport = HermesSessionStreamClient(),
    private val runApprovalCapabilitySource: RunApprovalCapabilitySource =
        RunApprovalCapabilitySource { false },
    /** P4: at most this many re-attach attempts after the initial stream. */
    private val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    /** P4: capped backoff (ms) per re-attach attempt index. */
    private val reconnectBackoffMs: LongArray = RECONNECT_BACKOFF_MS
) : StreamingChatSource {

    override fun streamTurn(
        profileId: String,
        sessionId: String,
        message: String
    ): Flow<ChatTurnEvent> = callbackFlow {
        // The message is validated and normalized before any profile,
        // credential or transport access: every injected transport only
        // ever receives a trimmed, valid message, and invalid input fails
        // closed without touching any store. The transport may validate
        // defensively again.
        val message = ChatMessageValidator.validate(message).getOrNull()
            ?: run {
                trySend(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput))
                close()
                return@callbackFlow
            }
        if (profileId.isBlank() || sessionId.isBlank()) {
            trySend(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput))
            close()
            return@callbackFlow
        }
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        }
        val credential = if (profile != null) {
            try {
                credentialStore.get(profileId)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (profile == null || credential == null || credential.isEmpty()) {
            trySend(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput))
            close()
            return@callbackFlow
        }
        val interactiveApprovals = try {
            runApprovalCapabilitySource.isEnabled(profileId)
        } catch (_: Exception) {
            false
        }

        // P4: re-attach across transient stream failures. The run is
        // independent of any single SSE socket server-side, so a
        // transient network/timeout failure with a confirmed run is
        // repaired by re-attaching to the SAME run from the confirmed
        // sequence state of the interrupted attempt. The loop runs on
        // the producer coroutine (cancellable at every suspension);
        // only the per-attempt listener crosses to the transport thread
        // and hands back exactly one buffered terminal event via the
        // deferred, so no mutable state crosses threads without a
        // happens-before edge.
        var runId: String? = null
        var attempts = 0
        var currentHandle: ChatStreamHandle? = null
        var resume: ChatStreamResumeState? = null
        try {
            while (true) {
                val attempt = attempts
                val finished = CompletableDeferred<Unit>()
                var pendingTerminal: ChatTurnEvent? = null
                val listener = object : ChatStreamListener {
                    override fun onEvent(event: ChatTurnEvent) {
                        when (event) {
                            is ChatTurnEvent.RunStarted -> {
                                // Forwarded to the collector AND
                                // captured: the confirmed run ID is
                                // the only value a re-attach may use.
                                if (runId == null) runId = event.runId
                                if (!trySendBlocking(event).isSuccess) {
                                    close()
                                }
                            }
                            is ChatTurnEvent.TurnCompleted,
                            is ChatTurnEvent.TurnFailed ->
                                // Buffered, never forwarded here: the
                                // producer decides in onFinished whether
                                // the turn ends or re-attaches. The
                                // transport delivers at most one
                                // terminal event per attempt.
                                pendingTerminal = event
                            else ->
                                // `trySendBlocking` on this synchronous,
                                // transport-owned background thread: when
                                // the default channel buffer is full it
                                // suspends the transport until the
                                // collector resumes — bounded
                                // backpressure, no event loss, order
                                // preserved. A failed send means the
                                // collector is gone: close the scope.
                                if (!trySendBlocking(event).isSuccess) {
                                    close()
                                }
                        }
                    }

                    override fun onFinished() {
                        // Completing the deferred hands the buffered
                        // terminal (if any) to the producer with a
                        // happens-before edge; it fires exactly once
                        // per attempt regardless of the outcome.
                        finished.complete(Unit)
                    }
                }
                currentHandle = try {
                    if (attempt == 0) {
                        streamTransport.openTurn(
                            baseUrl = profile.baseUrl,
                            apiKey = credential,
                            sessionId = sessionId,
                            message = message,
                            interactiveApprovals = interactiveApprovals,
                            listener = listener
                        )
                    } else {
                        // Re-attach to the same run from the confirmed
                        // state of the interrupted attempt.
                        streamTransport.openResume(
                            baseUrl = profile.baseUrl,
                            apiKey = credential,
                            sessionId = sessionId,
                            runId = runId ?: error("no confirmed run"),
                            resume = resume ?: error("no confirmed state"),
                            interactiveApprovals = interactiveApprovals,
                            listener = listener
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                val handle = currentHandle
                val opened = handle != null
                if (opened) {
                    // Suspends until this attempt's transport thread has
                    // delivered onFinished; a collector cancellation throws
                    // here and the finally below closes the handle.
                    finished.await()
                }
                // Handover from the transport thread (happens-before
                // via the deferred). Nulling the field before closing
                // keeps the close exactly once: either here (normal
                // attempt end) or in the finally (cancellation before
                // this point).
                val terminal = if (opened) pendingTerminal else null
                currentHandle = null
                // The handle must be closed exactly once even if taking
                // its snapshot failed: the try/finally keeps the
                // "stream never outlives the collector" contract.
                val snapshot = try {
                    handle?.resumeState()
                } finally {
                    handle?.close()
                }
                val failure = if (opened) {
                    (terminal as? ChatTurnEvent.TurnFailed)?.error
                } else {
                    // The transport failed synchronously before the
                    // (re-)attach could start: a transient transport
                    // failure without an attempt state of its own.
                    ChatStreamError.Network
                }
                // P4 fallback state: the failed attempt's own snapshot when
                // it has one — its stream was bound, so it may have advanced
                // the confirmed sequence — otherwise the state the attempt
                // was started from. A re-attach that never bound its stream
                // (an HTTP status failure such as a transient 5xx, or a
                // synchronous open failure) processed no frame, so the
                // previous state is still exact: `after_seq` is neither
                // reset nor replayed twice, and the confirmed run survives
                // the transport hiccup instead of ending the turn.
                val confirmed = snapshot ?: resume
                if (
                    failure != null &&
                    isReconnectable(failure) &&
                    attempt < maxReconnectAttempts &&
                    runId != null &&
                    confirmed != null &&
                    !confirmed.terminal
                ) {
                    // Swallow the transient failure and announce the
                    // re-attach: the turn continues, and the flow still
                    // terminates with exactly one terminal event.
                    if (!trySendBlocking(ChatTurnEvent.StreamReconnecting).isSuccess) {
                        return@callbackFlow
                    }
                    attempts = attempt + 1
                    resume = confirmed
                    // Bounded, non-zero fallback (P4), see
                    // [reconnectBackoffMsFor]: `attempt` is normally
                    // inside [reconnectBackoffMs] because the re-attach
                    // branch requires `attempt < maxReconnectAttempts`
                    // and the production ladder has one interval per
                    // attempt. A shorter ladder clamps to its last
                    // interval instead of falling back to 0 ms, so an
                    // unexpected attempt state can never turn into an
                    // aggressive 0 ms retry spin. Either way the whole
                    // ladder stays bounded by `maxReconnectAttempts`.
                    delay(reconnectBackoffMsFor(attempt, reconnectBackoffMs))
                    continue
                }
                if (terminal != null) {
                    if (!trySendBlocking(terminal).isSuccess) {
                        // The channel was already closed (the collector
                        // is gone) — the block may return, the
                        // callbackFlow contract is satisfied.
                        return@callbackFlow
                    }
                } else {
                    // The transport failed synchronously before the
                    // (re-)attach could start, or ended without a terminal
                    // event (a closed handle before the call started): fail
                    // closed with the generic reason.
                    trySend(ChatTurnEvent.TurnFailed(failure ?: ChatStreamError.Network))
                }
                // callbackFlow contract: the block must not return
                // while the channel is still open, so the settled turn
                // closes it here (idempotent if a failed send closed
                // it earlier).
                close()
                return@callbackFlow
            }
        } finally {
            currentHandle?.close()
        }
    }.flowOn(Dispatchers.IO)

    internal companion object {
        /** P4: at most this many re-attach attempts after the initial stream. */
        const val MAX_RECONNECT_ATTEMPTS = 5

        /** P4: capped exponential backoff between re-attach attempts. */
        val RECONNECT_BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 5_000L)

        /**
         * P4: the backoff interval (ms) for re-attach attempt [attempt].
         *
         * The ladder normally holds exactly one interval per attempt
         * ([RECONNECT_BACKOFF_MS] and [MAX_RECONNECT_ATTEMPTS] are
         * matched), so [attempt] is always in range. If a caller ever
         * combines a larger attempt bound with a shorter ladder, the
         * LAST interval is clamped instead of falling back to `0L`: an
         * unexpected attempt state must never degrade into a 0 ms
         * retry. Only a completely empty ladder yields `0L`, and even
         * then the number of attempts stays bounded by
         * `maxReconnectAttempts`.
         */
        internal fun reconnectBackoffMsFor(attempt: Int, ladder: LongArray): Long =
            ladder.getOrElse(attempt) { ladder.lastOrNull() ?: 0L }

        /**
         * Closed set of transient reasons repairable by a re-attach.
         * Everything else is definitive for the turn: a protocol
         * violation, a credential rejection, a rate limit, a TLS
         * failure or a server-side `error` frame is never retried.
         */
        private fun isReconnectable(error: ChatStreamError): Boolean =
            error == ChatStreamError.Network || error == ChatStreamError.Timeout
    }
}
