package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamTransport
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Performs the single authenticated
 * `POST /api/sessions/{session_id}/chat/stream` streaming request
 * (Mission 006A). This class is the transport only; profile lookup and
 * credential access happen in the repository layer, so the key never
 * reaches this class except as the scoped [SecretValue] argument.
 *
 * Stream completion:
 * - the confirmed server contract ends every complete turn with
 *   `run.completed`/`error` followed by `done`; the terminal
 *   [ChatTurnEvent.TurnCompleted]/[ChatTurnEvent.TurnFailed] is
 *   buffered internally and delivered exactly once only after the
 *   stream has sent a valid `done`; once that `done` frame has been
 *   processed the turn ends immediately - the end-of-stream state is
 *   validated, the response is closed and `onFinished()` is called -
 *   without waiting for EOF or a read timeout, and no further byte or
 *   frame is processed;
 * - a stream that ends after the terminal frame but before `done` is
 *   a truncated stream and fails with
 *   [ChatStreamError.IncompatibleApi]; a stream that never reached a
 *   terminal frame ends with [ChatStreamError.Network];
 * - the production read timeout
 *   ([STREAM_READ_TIMEOUT_SECONDS]) is above the confirmed server
 *   keepalive interval ([SERVER_SSE_KEEPALIVE_SECONDS]).
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the session ID is added as exactly one encoded path segment via
 *   `addPathSegment`;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - the body is exactly one `message` field (trimmed), nothing else;
 * - redirects and connection retries are disabled; the POST is never
 *   repeated automatically;
 * - the whole stream is hard-bounded to [MAX_STREAM_BYTES] body bytes
 *   by [LimitingInputStream]: the decoder and the parser never see
 *   more, and a further byte past the limit fails closed with
 *   [ChatStreamError.IncompatibleApi]; a body of exactly
 *   [MAX_STREAM_BYTES] bytes still ends with a regular EOF;
 * - the body is decoded as strict UTF-8: malformed or unmappable bytes
 *   fail closed with [ChatStreamError.IncompatibleApi] and are never
 *   replaced by replacement characters;
 * - every failure maps to a closed [ChatStreamError]; no response
 *   body, exception text, URL, key, session ID, server error message,
 *   frame or payload ever appears in an event, result or log.
 *
 * Threading: [ChatStreamTransport.openTurn] returns immediately after
 * starting a dedicated reader thread; all [ChatStreamListener]
 * callbacks arrive on that thread. The listener sink must be
 * thread-safe. Closing the returned [ChatStreamHandle] cancels the
 * underlying OkHttp call and suppresses any further event, including
 * a terminal failure.
 */
class HermesSessionStreamClient(
    okHttpClient: OkHttpClient = defaultSessionStreamHttpClient()
) : ChatStreamTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun openTurn(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        message: String,
        listener: ChatStreamListener
    ): ChatStreamHandle {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return failNow(listener, ChatStreamError.InvalidInput)
        if (!isSafeSessionId(sessionId)) {
            return failNow(listener, ChatStreamError.InvalidInput)
        }
        val keyIsSafe = apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!keyIsSafe) {
            return failNow(listener, ChatStreamError.InvalidInput)
        }
        val trimmedMessage = ChatMessageValidator.validate(message).getOrNull()
            ?: return failNow(listener, ChatStreamError.InvalidInput)
        val bodyJson = try {
            JSONObject().put(MESSAGE_FIELD, trimmedMessage).toString()
        } catch (_: Exception) {
            return failNow(listener, ChatStreamError.InvalidInput)
        }

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(API_SEGMENT)
            .addPathSegment(SESSIONS_SEGMENT)
            .addPathSegment(sessionId)
            .addPathSegment(CHAT_SEGMENT)
            .addPathSegment(STREAM_SEGMENT)
            .build()
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(bodyJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .header(CONTENT_TYPE_HEADER, CONTENT_TYPE_HEADER_VALUE)
                .header(ACCEPT_HEADER, SSE_CONTENT_TYPE)
                .build()
        }

        val handle = StreamHandle()
        handle.bind(client.newCall(request))
        Thread({
            runTurn(handle, sessionId, listener)
        }, "hermes-chat-stream").start()
        return handle
    }

    private fun runTurn(handle: StreamHandle, sessionId: String, listener: ChatStreamListener) {
        val finished = AtomicBoolean()
        val responseRef = arrayOfNulls<Response>(1)

        // Confirmed server contract: a complete turn ends with
        // `assistant.completed` -> `run.completed` -> `done` (or
        // `error` -> `done`). The pipeline emits the terminal event as
        // soon as it sees `run.completed`/`error`, so the terminal
        // event is only buffered here and released once the stream has
        // also sent a valid `done`. As soon as a `done` frame has been
        // processed without a protocol violation the turn ends
        // immediately: the end-of-stream state is validated, the
        // response is closed and the buffered terminal event is
        // released - without waiting for EOF or a read timeout. No
        // byte or frame after `done` is ever processed.
        var terminalEvent: ChatTurnEvent? = null
        var doneSent = false

        fun finish(event: ChatTurnEvent?) {
            if (finished.compareAndSet(false, true)) {
                // The event is released through the same handle monitor
                // as every other delivery, so it cannot race a
                // concurrent close. The completion signal itself is not
                // an "event": it must fire exactly once regardless of
                // whether the handle was closed.
                try {
                    handle.deliver { event?.let(listener::onEvent) }
                } finally {
                    listener.onFinished()
                }
            }
        }

        val call = handle.call ?: run {
            finish(null)
            return
        }
        try {
            val response: Response = call.execute()
            responseRef[0] = response
            if (handle.isClosed) {
                response.close()
                finish(null)
                return
            }
            val reason = statusReason(response)
            if (reason != null) {
                response.close()
                finish(ChatTurnEvent.TurnFailed(reason))
                return
            }
            val contentType = response.header(CONTENT_TYPE_HEADER)
            if (!isSseContentType(contentType)) {
                response.close()
                finish(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi))
                return
            }
            val parser = SseFrameParser(terminalEventName = DONE_FRAME)
            val pipeline = ChatStreamEventPipeline(sessionId, requireDoneAfterTerminal = true)
            // Hard bound: at most [MAX_STREAM_BYTES] body bytes ever
            // reach the decoder or the parser. A further read past the
            // limit fails closed with [StreamLimitException] if any
            // byte remains; an exactly exhausted body ends with a
            // regular EOF at the limit.
            val limited = LimitingInputStream(response.body!!.byteStream(), MAX_STREAM_BYTES)
            // Strict UTF-8: malformed or unmappable bytes must fail
            // closed with a [CharacterCodingException], never be
            // replaced by U+FFFD.
            val reader = InputStreamReader(
                limited,
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            )
            val buffer = CharArray(BUFFER_SIZE)
            try {
                while (!doneSent) {
                    val read = reader.read(buffer)
                    if (read == -1) {
                        break
                    }
                    val frames = parser.feed(String(buffer, 0, read))
                    for (frame in frames) {
                        val events = pipeline.process(frame)
                        handle.deliver {
                            for (event in events) {
                                if (
                                    event is ChatTurnEvent.TurnCompleted ||
                                    event is ChatTurnEvent.TurnFailed
                                ) {
                                    // Buffer the terminal event; the stream is
                                    // still incomplete until it sends `done`.
                                    terminalEvent = event
                                } else {
                                    listener.onEvent(event)
                                }
                            }
                        }
                        // A valid `done` is the last frame of a complete
                        // turn: stop processing this chunk's remaining
                        // frames (and read nothing further) immediately,
                        // without waiting for EOF or a read timeout. No
                        // parsed frame after `done` ever reaches the
                        // pipeline.
                        if (frame.eventName == DONE_FRAME) {
                            doneSent = true
                            break
                        }
                    }
                    if (doneSent) {
                        break
                    }
                }
            } finally {
                response.close()
            }
            if (handle.isClosed) {
                finish(null)
                return
            }
            // The read loop stopped either on a valid `done` or on
            // EOF. With a valid `done` only the pipeline state is
            // validated: the dispatched `done` frame guarantees its own
            // empty frame boundary, so no partial frame can precede
            // it, and any bytes after `done` (a partial line or further
            // frames) are semantically outside the turn and are
            // ignored - the response is closed. Only on physical EOF
            // without `done` does the parser still have to prove it
            // saw no partial frame, and the pipeline must have seen
            // `done` after the terminal frame.
            try {
                if (!doneSent) {
                    parser.endOfStream()
                }
                pipeline.endOfStream()
            } catch (_: SseFrameParserException) {
                finish(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi))
                return
            } catch (_: ChatTurnProtocolException) {
                // A stream that ended after the terminal frame but
                // before `done` is truncated, never a success.
                finish(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi))
                return
            }
            if (terminalEvent != null) {
                // The turn ended with a valid `done`: release the
                // buffered terminal event exactly once, then finish.
                finish(terminalEvent)
                return
            }
            // EOF before any terminal frame: the stream ended
            // incompletely.
            finish(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        } catch (_: CharacterCodingException) {
            // Malformed or unmappable UTF-8 in the stream body (the
            // strict decoder reports `MalformedInputException`/
            // `UnmappableCharacterException`): a closed protocol
            // violation, never a silent replacement character.
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            }
        } catch (_: ChatTurnProtocolException) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            }
        } catch (_: SseFrameParserException) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            }
        } catch (_: StreamLimitException) {
            // The body sent more bytes than [MAX_STREAM_BYTES]: the
            // stream is not the expected bounded contract.
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            }
        } catch (_: SocketTimeoutException) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.Timeout)
            }
        } catch (_: SSLException) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.Tls)
            }
        } catch (_: InterruptedIOException) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.Timeout)
            }
        } catch (_: IOException) {
            // Includes OkHttp's CanceledException after [ChatStreamHandle.close].
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.Network)
            }
        } catch (_: Exception) {
            finishIfNotClosed(handle, finished, listener, responseRef, terminalEvent != null) {
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            }
        }
    }

    /**
     * Closes an open response if the turn thread was interrupted
     * mid-stream and finishes the listener. [sawTerminalFrame] is
     * `true` once the pipeline has seen `run.completed` or `error`
     * without a following `done`: an interruption in that window is a
     * truncated stream ([ChatStreamError.IncompatibleApi]), not the
     * generic interruption reason of [event].
     */
    private fun finishIfNotClosed(
        handle: StreamHandle,
        finished: AtomicBoolean,
        listener: ChatStreamListener,
        responseRef: Array<out Response?>,
        sawTerminalFrame: Boolean,
        event: () -> ChatTurnEvent
    ) {
        responseRef[0]?.close()
        // The completion signal fires exactly once (CAS), and the final
        // event is released through the SAME handle monitor as every
        // other delivery. That removes the check-then-act window where a
        // concurrent close() could land between an `isClosed` read and
        // `onEvent`, which would violate the "no further events after
        // close" transport contract on this exception path too.
        if (finished.compareAndSet(false, true)) {
            try {
                handle.deliver {
                    val finalEvent = if (sawTerminalFrame) {
                        ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
                    } else {
                        event()
                    }
                    listener.onEvent(finalEvent)
                }
            } finally {
                listener.onFinished()
            }
        }
    }

    /** @return the closed reason for a non-200 answer, or `null` for 200. */
    private fun statusReason(response: Response): ChatStreamError? = when (response.code) {
        OK_CODE -> null
        401, 403 -> ChatStreamError.InvalidCredentials
        404 -> ChatStreamError.SessionNotFound
        409 -> ChatStreamError.Conflict
        429 -> ChatStreamError.RateLimited
        else -> ChatStreamError.Http
    }

    private fun isSseContentType(header: String?): Boolean {
        val type = header?.substringBefore(';')?.trim() ?: return false
        return SSE_CONTENT_TYPE.equals(type, ignoreCase = true)
    }

    private fun isSafeSessionId(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        if (sessionId.length > MAX_SESSION_ID_LENGTH) return false
        return sessionId.none { it.isISOControl() }
    }

    private fun failNow(
        listener: ChatStreamListener,
        error: ChatStreamError
    ): ChatStreamHandle {
        try {
            listener.onEvent(ChatTurnEvent.TurnFailed(error))
        } finally {
            listener.onFinished()
        }
        return NoopStreamHandle
    }

    private class StreamHandle : ChatStreamHandle {
        @Volatile
        var isClosed = false
            private set

        @Volatile
        var call: Call? = null
            private set

        fun bind(newCall: Call) {
            call = newCall
        }

        /**
         * Runs one event delivery while holding the handle monitor, and
         * only if the handle has not been closed yet. [close] takes the
         * same monitor, so a delivery and a close can never interleave:
         * an in-flight event either completes before the close latches or
         * is suppressed entirely. This is what makes the transport
         * contract "no further events after close" hold for events that
         * are dispatched from the reader thread.
         */
        @Synchronized
        fun deliver(block: () -> Unit) {
            if (!isClosed) {
                block()
            }
        }

        @Synchronized
        override fun close() {
            if (!isClosed) {
                isClosed = true
                call?.cancel()
            }
        }
    }

    private object NoopStreamHandle : ChatStreamHandle {
        override fun close() {
            // nothing to cancel
        }
    }

    /**
     * Hard byte bound for one streaming body (Mission 006A). At most
     * [maxBytes] source bytes are ever handed to the reader. The very
     * next read attempt after the budget is exhausted probes the source
     * for at most one recognition byte: if a further byte exists the
     * stream is larger than the closed contract and that read - and
     * every later read - fails with [StreamLimitException]; if the
     * source has ended at exactly [maxBytes], that probe is recorded as
     * a terminal EOF and this read - and every later read - returns a
     * regular `-1` without ever touching the source again. The
     * recognition probe therefore runs at most once per stream, and a
     * regular EOF is never reported as a limit violation. The
     * exception carries no dynamic data.
     */
    internal class LimitingInputStream(
        private val source: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var delivered: Long = 0L
        // The probe runs at most once and its outcome is latched:
        // `limitExceeded` when a recognition byte existed,
        // `sourceExhausted` when the probe found EOF at the exact
        // limit. Both are permanent states.
        private var limitExceeded = false
        private var sourceExhausted = false

        override fun read(): Int {
            if (limitExceeded) throw StreamLimitException()
            if (sourceExhausted) return -1
            if (delivered >= maxBytes) {
                if (source.read() >= 0) {
                    limitExceeded = true
                    throw StreamLimitException()
                }
                sourceExhausted = true
                return -1
            }
            val value = source.read()
            if (value >= 0) delivered += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (limitExceeded) throw StreamLimitException()
            if (sourceExhausted) return -1
            if (delivered >= maxBytes) {
                if (source.read() >= 0) {
                    limitExceeded = true
                    throw StreamLimitException()
                }
                sourceExhausted = true
                return -1
            }
            val remaining = (maxBytes - delivered).toInt().coerceAtMost(len)
            val read = source.read(b, off, remaining)
            if (read > 0) delivered += read
            return read
        }
    }

    // `internal` so the production default configuration can be
    // verified directly from the unit tests without reflection.
    internal companion object {
        const val MAX_STREAM_BYTES = 8L * 1024 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val MAX_SESSION_ID_LENGTH = 256
        const val BUFFER_SIZE = 8 * 1024
        const val API_SEGMENT = "api"
        const val SESSIONS_SEGMENT = "sessions"
        const val CHAT_SEGMENT = "chat"
        const val STREAM_SEGMENT = "stream"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CONTENT_TYPE_HEADER_VALUE = "application/json"
        const val ACCEPT_HEADER = "Accept"
        const val SSE_CONTENT_TYPE = "text/event-stream"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MESSAGE_FIELD = "message"
        const val OK_CODE = 200

        /** The final frame of a complete turn; no frame may follow it. */
        const val DONE_FRAME = "done"

        /**
         * Confirmed against the installed Hermes server
         * (`gateway/platforms/api_server.py`):
         * `CHAT_COMPLETIONS_SSE_KEEPALIVE_SECONDS = 30.0`. Between two
         * keepalive comments the SSE body can be idle for up to 30 s.
         */
        const val SERVER_SSE_KEEPALIVE_SECONDS = 30L

        /**
         * Production read timeout of the streaming connection. It is
         * deliberately above the confirmed 30 s keepalive interval so a
         * normal idle pause between keepalives never aborts the turn
         * (the old 10 s default fired between two keepalives), and it
         * stays far below any bound where a dead connection would go
         * unnoticed for an unreasonably long time.
         */
        const val STREAM_READ_TIMEOUT_SECONDS = 45L

        /**
         * Builds the production default streaming client. Tests may
         * keep their own short timeouts; this factory is the single
         * configurable production default.
         */
        fun newDefaultStreamClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // The streaming read is bounded by the stream size limit and
            // by the collector; a global call timeout would kill long
            // model answers, so it is intentionally left at 0 (none).
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        fun defaultSessionStreamHttpClient(): OkHttpClient = newDefaultStreamClient()
    }
}

/**
 * Closed streaming-contract violation: the response body sent more
 * bytes than the hard bound of the turn. Always maps to
 * [ChatStreamError.IncompatibleApi]; carries no dynamic data.
 */
class StreamLimitException : Exception()
