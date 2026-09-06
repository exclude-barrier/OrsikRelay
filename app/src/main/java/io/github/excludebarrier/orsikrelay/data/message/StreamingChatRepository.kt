package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamTransport
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.network.HermesSessionStreamClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
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
 * directly: the transport-owned background thread forwards events with
 * `trySendBlocking`, so a full default channel buffer applies bounded
 * backpressure to the transport instead of dropping a valid stream, and
 * a blocked send is unblocked as soon as the collector is gone (scope
 * closed or canceled). The terminal `onFinished` closes the scope, and
 * `awaitClose` binds the underlying OkHttp call to the collector's
 * cancellation, so the stream never outlives the collector. If a send
 * fails (collector canceled or already finished), the scope is closed
 * instead of letting the stream run on silently.
 *
 * Every failure maps to a closed [ChatStreamError] reason: no profile
 * data, URL, key, session ID or exception text ever escapes into the
 * flow. An incomplete or missing profile (metadata, credential or an
 * empty key) is reported as [ChatStreamError.InvalidInput] — the closed
 * streaming error set has no profile-specific variant.
 *
 * The whole flow runs on [Dispatchers.IO] via [flowOn], so the profile
 * and credential reads (SharedPreferences and Keystore) and the stream
 * opening never execute on the collecting (main) thread. The flow is
 * never retried.
 */
class StreamingChatRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val streamTransport: ChatStreamTransport = HermesSessionStreamClient()
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
        val handle = try {
            streamTransport.openTurn(
                baseUrl = profile.baseUrl,
                apiKey = credential,
                sessionId = sessionId,
                message = message,
                listener = object : ChatStreamListener {
                    override fun onEvent(event: ChatTurnEvent) {
                        // `trySendBlocking` (kotlinx.coroutines.channels)
                        // on this synchronous, transport-owned background
                        // thread: when the default channel buffer is full
                        // it suspends the transport until the collector
                        // resumes — bounded backpressure, no event loss,
                        // order preserved. It never suspends when the
                        // scope is closed or canceled (the channel is
                        // finished), so the failed send below reliably
                        // closes the scope; `awaitClose` then closes the
                        // transport handle exactly once.
                        if (!trySendBlocking(event).isSuccess) {
                            close()
                        }
                    }

                    override fun onFinished() {
                        close()
                    }
                }
            )
        } catch (_: Exception) {
            null
        }
        if (handle == null) {
            trySend(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
            close()
            return@callbackFlow
        }
        // If the transport finished synchronously before this point the
        // scope is already closed and the collector is already complete;
        // `awaitClose` then runs its close action immediately. Collector
        // cancellation is bound to the scope via `awaitClose`, whose close
        // action executes exactly once on scope close (normal completion or
        // cancellation) — the single lifecycle-safe close guarantee for the
        // transport handle.
        awaitClose {
            handle.close()
        }
    }.flowOn(Dispatchers.IO)
}
