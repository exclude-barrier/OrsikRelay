package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamResumeState
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Strict, stateful pipeline for one streaming chat turn (Mission
 * 006A). It consumes the closed [ParsedSseFrame] frames of
 * [SseFrameParser] and produces only closed [ChatTurnEvent] values.
 *
 * Enforced rules:
 * - `session_id` must exactly equal the requested session ID;
 * - `run_id` must be non-blank, at most [MAX_ID_LENGTH] characters and
 *   free of ISO control characters; all frames of the turn share one
 *   run ID;
 * - `message_id` must be non-blank, bounded and free of ISO control
 *   characters; exactly one assistant message may be started;
 * - `seq` is a positive exact integer; the first event of the turn is
 *   exactly `seq = 1` and every following event is exactly
 *   `lastSeq + 1`. Backward-going sequences, gaps, contradictory
 *   resubmissions and values that do not fit in a `Long` are rejected
 *   closed; a resubmitted, byte-identical frame (same event name and
 *   payload) is dropped and never applied twice;
 * - `ts` is a finite, non-negative number (fractions allowed);
 * - the closed event order of the success path is enforced, including
 *   that `run.completed` is only allowed after `assistant.completed`;
 * - unknown event names are rejected closed;
 * - with [requireDoneAfterTerminal], a stream that ends after the
 *   terminal `run.completed`/`error` frame but before `done` is
 *   rejected by [endOfStream];
 * - `tool.progress` and all reasoning events are silently ignored, but
 *   only inside the assistant message window (after `message.started`,
 *   before `assistant.completed`); outside that window every tool and
 *   reasoning event is rejected closed;
 * - no server field except the closed, UI-safe parts is propagated:
 *   no `user_message`, `runtime`, `usage`, tool names, arguments,
 *   previews, error messages or raw payloads. The single exception is
 *   `pending_steer` on `run.completed` (Mission 008B): a non-blank
 *   string value is preserved exactly on
 *   [ChatTurnEvent.TurnCompleted]; absent, null, non-string or blank
 *   values stay `null`.
 *
 * Every protocol violation raises [ChatTurnProtocolException] with the
 * closed reason [ChatStreamError.IncompatibleApi]. The exception
 * carries no dynamic data.
 */
class ChatStreamEventPipeline(
    private val expectedSessionId: String,
    /**
     * When `true` (production transport), end-of-stream is only
     * accepted after the stream sent `done` following a terminal
     * `run.completed` or `error` frame: a stream that ends after the
     * terminal frame but before `done` is incomplete and
     * [endOfStream] fails closed. When `false`, [endOfStream]
     * performs no additional check and no `done` is required.
     */
    requireDoneAfterTerminal: Boolean = false,
    private val interactiveApprovals: Boolean = false,
    /**
     * P4 re-attach: the confirmed sequence state of an interrupted
     * stream of the SAME run. When non-null the pipeline starts from
     * that state: the run is already bound and started (no
     * `run.started` frame is expected again), and the next frame must
     * carry exactly `resume.lastSeq + 1` — the sequence check is
     * unchanged, so every other shape fails closed.
     */
    resume: ChatStreamResumeState? = null
) {
    private val requireDoneAfterTerminal = requireDoneAfterTerminal
    private var boundRunId: String? = null
    private var lastSeq: Long = 0L
    // seq -> digest of (event name, payload), so a resubmitted `seq` with a
    // different event name or payload is rejected as contradictory while an
    // exactly identical frame is dropped. Only the digest is stored; the
    // payload itself is never kept.
    // Bounded window: only the most recent SEEN_SEQS_WINDOW digests are kept.
    // A strictly increasing stream only ever resubmits the immediately
    // preceding seq, so this preserves dedup behaviour while bounding memory.
    private val seenSeqs = object : LinkedHashMap<Long, ByteArray>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, ByteArray>?
        ): Boolean = size > SEEN_SEQS_WINDOW
    }
    private var runStarted = false
    private var messageStarted = false
    private var boundMessageId: String? = null
    private var composed = StringBuilder()
    private var assistantCompleted = false
    private var finalText: String? = null
    private var runCompleted = false
    private var sawError = false
    private var doneSent = false
    init {
        // P4 re-attach: start from the confirmed state of the
        // interrupted stream. A non-null snapshot always carries a
        // confirmed run (boundRunId is set by the first frame, which
        // must be the `seq = 1` `run.started`), so `runStarted` is
        // restored to `true`.
        if (resume != null) {
            boundRunId = resume.runId
            lastSeq = resume.lastSeq
            runStarted = true
            messageStarted = resume.messageStarted
            boundMessageId = resume.messageId
            composed = StringBuilder(resume.composed)
            assistantCompleted = resume.assistantCompleted
            finalText = resume.finalText
            runCompleted = resume.runCompleted
            sawError = resume.sawError
        }
    }

    /**
     * Validates one frame and returns the closed events it produces.
     * A frame can produce zero events (ignored keepalive-style frames,
     * duplicate `seq`, `tool.progress`, reasoning) or exactly one.
     *
     * @throws ChatTurnProtocolException on any protocol violation.
     */
    fun process(frame: ParsedSseFrame): List<ChatTurnEvent> {
        val name = frame.eventName
            ?: throw protocol()
        val dataText = frame.data
            ?: throw protocol()
        val payload = try {
            JSONObject(dataText)
        } catch (_: Exception) {
            throw protocol()
        }
        if (!payload.has(SESSION_ID_FIELD) || payload.isNull(SESSION_ID_FIELD)) {
            throw protocol()
        }
        val sessionId = payload.opt(SESSION_ID_FIELD) as? String
            ?: throw protocol()
        if (sessionId != expectedSessionId) {
            throw protocol()
        }
        val runId = strictId(payload, RUN_ID_FIELD)
        if (boundRunId == null) {
            boundRunId = runId
        } else if (boundRunId != runId) {
            throw protocol()
        }
        val (seq, duplicate) = checkSequence(name, dataText, payload)
        validateTimestamp(payload)
        if (duplicate) {
            // A duplicate `run_id`/`seq` pair is dropped, never applied
            // twice, for every event type.
            return emptyList()
        }
        if (doneSent) {
            // Any new frame after `done` contradicts the confirmed
            // stream order; it is rejected closed.
            throw protocol()
        }
        val events = dispatch(name, payload)
        // The sequence state is committed only after the frame is fully
        // validated: a rejected frame must not consume its `seq`.
        seenSeqs[seq] = frameIdentity(name, dataText)
        lastSeq = seq
        return events
    }

    /**
     * Validates the end of the stream. A [requireDoneAfterTerminal]
     * pipeline rejects a stream that ended after the terminal frame
     * but before `done`, because such a stream cannot be distinguished
     * from a truncated one.
     *
     * A stream that ended without any terminal frame - no
     * `run.completed` and no `error` - is deliberately NOT a protocol
     * violation: the connection simply died mid-stream (P4). The run
     * is still alive server-side, so the transport maps this end to a
     * reconnectable [ChatStreamError.Network] and the repository
     * re-attaches to the same run instead of failing the turn closed.
     * Only a malformed frame, a wrong order or a wrong identity is
     * [ChatStreamError.IncompatibleApi].
     *
     * @throws ChatTurnProtocolException when the stream sent a
     * terminal frame but no valid `done` after it.
     */
    fun endOfStream() {
        if (requireDoneAfterTerminal && !doneSent && (runCompleted || sawError)) {
            throw protocol()
        }
    }

    /**
     * P4 re-attach snapshot of the confirmed sequence state, or `null`
     * while no frame has been processed (no run confirmed). The
     * transport exposes it on the stream handle; callers must read it
     * only after the stream has finished.
     */
    fun snapshot(): ChatStreamResumeState? {
        val runId = boundRunId ?: return null
        return ChatStreamResumeState(
            runId = runId,
            lastSeq = lastSeq,
            messageStarted = messageStarted,
            messageId = boundMessageId,
            composed = composed.toString(),
            assistantCompleted = assistantCompleted,
            finalText = finalText,
            runCompleted = runCompleted,
            sawError = sawError
        )
    }

    /**
     * Applies one fully validated frame and produces the closed events.
     * A frame can produce zero events (ignored keepalive-style frames,
     * `tool.progress`, reasoning, `done`) or exactly one.
     *
     * @throws ChatTurnProtocolException on any protocol violation.
     */
    private fun dispatch(name: String, payload: JSONObject): List<ChatTurnEvent> {
        return when (name) {
            RUN_STARTED -> {
                if (runStarted || runCompleted || sawError) {
                    throw protocol()
                }
                runStarted = true
                return listOf(ChatTurnEvent.RunStarted(boundRunId ?: throw protocol()))
            }
            MESSAGE_STARTED -> {
                if (!runStarted || messageStarted || runCompleted || sawError) {
                    throw protocol()
                }
                val message = payload.opt(MESSAGE_FIELD) as? JSONObject
                    ?: throw protocol()
                // The nested object carries the message id under `id`,
                // not under the top-level `message_id`.
                val id = strictId(message, NESTED_MESSAGE_ID_FIELD)
                if (message.opt(ROLE_FIELD) != ROLE_ASSISTANT) {
                    throw protocol()
                }
                messageStarted = true
                boundMessageId = id
                return listOf(ChatTurnEvent.AssistantStarted)
            }
            ASSISTANT_DELTA -> {
                if (!messageStarted || assistantCompleted) {
                    throw protocol()
                }
                val id = strictId(payload, MESSAGE_ID_FIELD)
                if (id != boundMessageId) {
                    throw protocol()
                }
                val delta = payload.opt(DELTA_FIELD) as? String
                    ?: throw protocol()
                if (delta.length > MAX_DELTA_LENGTH) {
                    throw protocol()
                }
                if (composed.length + delta.length > MAX_COMPOSED_LENGTH) {
                    throw protocol()
                }
                composed.append(delta)
                return listOf(ChatTurnEvent.AssistantDelta(delta))
            }
            ASSISTANT_COMPLETED -> {
                if (!messageStarted || assistantCompleted) {
                    throw protocol()
                }
                val id = strictId(payload, MESSAGE_ID_FIELD)
                if (id != boundMessageId) {
                    throw protocol()
                }
                val content = if (payload.has(CONTENT_FIELD) && !payload.isNull(CONTENT_FIELD)) {
                    payload.opt(CONTENT_FIELD) as? String ?: throw protocol()
                } else {
                    null
                }
                if (content != null && content.length > MAX_COMPOSED_LENGTH) {
                    throw protocol()
                }
                finalText = content
                assistantCompleted = true
                return listOf(
                    ChatTurnEvent.AssistantCompleted(
                        finalText ?: composed.toString()
                    )
                )
            }
            APPROVAL_REQUEST -> {
                // Protocol v1 is capability-gated and has an exact wire
                // allowlist. Any additional field (including command, args,
                // path, output or pattern keys) rejects the frame closed.
                if (
                    !interactiveApprovals ||
                    !runStarted ||
                    !messageStarted ||
                    assistantCompleted ||
                    runCompleted ||
                    sawError ||
                    payloadKeys(payload) != APPROVAL_V1_FIELDS
                ) {
                    throw protocol()
                }
                val requestId = strictId(payload, REQUEST_ID_FIELD)
                val runId = boundRunId ?: throw protocol()
                if (
                    !ApprovalRequest.isSafeRunId(runId) ||
                    !ApprovalRequest.isSafeRequestId(requestId)
                ) {
                    throw protocol()
                }
                val description = payload.opt(DESCRIPTION_FIELD) as? String
                    ?: throw protocol()
                if (description != ApprovalRequest.SAFE_DESCRIPTION) {
                    throw protocol()
                }
                val choices = payload.opt(CHOICES_FIELD) as? org.json.JSONArray
                    ?: throw protocol()
                if (
                    choices.length() != 2 ||
                    choices.opt(0) != APPROVAL_ONCE ||
                    choices.opt(1) != APPROVAL_DENY
                ) {
                    throw protocol()
                }
                return listOf(
                    ChatTurnEvent.ApprovalRequested(
                        ApprovalRequest(
                            runId = runId,
                            requestId = requestId,
                            description = description
                        )
                    )
                )
            }
            TOOL_STARTED, TOOL_COMPLETED, TOOL_FAILED -> {
                if (!messageStarted || assistantCompleted || runCompleted || sawError) {
                    throw protocol()
                }
                // Only a conservative tool identifier crosses this boundary;
                // args, commands, previews and output remain private.
                return listOf(ChatTurnEvent.ToolActivity(safeToolName(payload)))
            }
            RUN_COMPLETED -> {
                if (runCompleted || sawError || !assistantCompleted) {
                    throw protocol()
                }
                // `messages`, `usage`, `runtime` are deliberately neither
                // parsed nor logged. `pending_steer` is the only field
                // that crosses the boundary (Mission 008B): a non-blank
                // string is preserved exactly; every other shape (absent,
                // null, non-string, blank) yields `null`.
                val pendingSteer = safePendingSteer(payload.opt(PENDING_STEER_FIELD) as? String)
                runCompleted = true
                return listOf(ChatTurnEvent.TurnCompleted(pendingSteer))
            }
            DONE -> {
                if (!runCompleted && !sawError) {
                    throw protocol()
                }
                // `done` after the terminal frame marks the stream as
                // complete; the transport only releases the buffered
                // terminal event after this point.
                doneSent = true
                return emptyList()
            }
            ERROR -> {
                if (sawError || runCompleted) {
                    throw protocol()
                }
                // The server's own in-stream `error` frame (always followed
                // by `done`) is an AUTHORITATIVE terminal: the server set a
                // terminal run status. It maps to the dedicated
                // [ChatStreamError.ServerError] so the caller can tell it
                // apart from a non-2xx HTTP answer ([ChatStreamError.Http],
                // a proven pre-run rejection; [ChatStreamError.ServerUnavailable],
                // an ambiguous 5xx) and from a local transport/protocol failure. The
                // dynamic `message` field is intentionally ignored.
                sawError = true
                return listOf(ChatTurnEvent.TurnFailed(ChatStreamError.ServerError))
            }
            TOOL_PROGRESS,
            REASONING_STARTED,
            REASONING_DELTA,
            REASONING_COMPLETED -> {
                // Silently ignored by design, but only inside the
                // assistant message window of the confirmed order:
                // after `message.started` and before
                // `assistant.completed`. Outside that window they
                // contradict the stream order and are rejected closed
                // instead of silently dropped.
                if (!messageStarted || assistantCompleted || runCompleted || sawError) {
                    throw protocol()
                }
                return emptyList()
            }
            else -> {
                // Unknown event: rejected closed.
                throw protocol()
            }
        }
    }

    /**
     * Read-only sequence check. Returns the parsed `seq` plus `true`
     * when this exact `run_id`/`seq` frame was already seen and must
     * be dropped, `false` for a new `seq`. The first event of the
     * turn must be `seq = 1`; every following event must be exactly
     * `lastSeq + 1`. A resubmission of a byte-identical frame is the
     * only resubmission allowed: the same `seq` with a different event
     * name or payload is rejected as contradictory. A backward-going
     * new `seq` and a gap (`seq > lastSeq + 1`) are rejected. No state
     * is committed here; the caller commits only after the frame is
     * fully validated.
     */
    private fun checkSequence(
        eventName: String,
        dataText: String,
        payload: JSONObject
    ): Pair<Long, Boolean> {
        val seq = strictPositiveInt(payload, SEQ_FIELD)
        if (lastSeq == 0L) {
            // First event of the turn: the sequence starts exactly at
            // 1.
            if (seq != FIRST_SEQUENCE) {
                throw protocol()
            }
            return seq to false
        }
        if (seq < lastSeq) {
            // Backward-going `seq`.
            val previous = seenSeqs[seq]
            if (previous != null) {
                val current = frameIdentity(eventName, dataText)
                if (previous.contentEquals(current)) {
                    return seq to true // identical duplicate: dropped, never applied twice
                }
                throw protocol() // same seq, different event or payload: contradictory
            }
            throw protocol() // backward-going new seq
        }
        if (seq == lastSeq + 1L) {
            // The normal case: the very next sequence number.
            return seq to false
        }
        if (seq > lastSeq + 1L) {
            // Gap: the stream skipped a sequence number.
            throw protocol()
        }
        // `seq == lastSeq`: only an identical resubmission is allowed.
        val previous = seenSeqs[seq]
            ?: throw protocol()
        val current = frameIdentity(eventName, dataText)
        if (!previous.contentEquals(current)) {
            throw protocol() // same seq, different event or payload: contradictory
        }
        return seq to true
    }

    /**
     * Deterministic fingerprint of the semantic event identity: the
     * event name plus the complete payload text. Only the digest is
     * stored in memory; the payload itself is neither kept, logged nor
     * surfaced.
     */
    private fun frameIdentity(eventName: String, dataText: String): ByteArray {
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (_: java.security.NoSuchAlgorithmException) {
            throw protocol()
        }
        digest.update(eventName.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(dataText.toByteArray(StandardCharsets.UTF_8))
        return digest.digest()
    }

    private fun validateTimestamp(payload: JSONObject) {
        if (!payload.has(TS_FIELD) || payload.isNull(TS_FIELD)) {
            throw protocol()
        }
        val value = payload.opt(TS_FIELD) as? Number
            ?: throw protocol()
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw protocol()
        }
        // `BigDecimal` cannot represent non-finite values; "NaN" and
        // "Infinity" strings already failed the parse above.
        if (decimal.signum() < 0 || decimal > LONG_MAX_DECIMAL) {
            throw protocol()
        }
    }

    private fun strictId(json: JSONObject, key: String): String {
        val value = json.opt(key) as? String
            ?: throw protocol()
        if (value.isBlank() || value.length > MAX_ID_LENGTH) {
            throw protocol()
        }
        if (value.any { it.isISOControl() }) {
            throw protocol()
        }
        return value
    }

    private fun safeToolName(json: JSONObject): String? {
        val value = json.opt(TOOL_NAME_FIELD) as? String ?: return null
        if (value.isBlank() || value.length > MAX_TOOL_NAME_LENGTH) return null
        if (!TOOL_NAME_REGEX.matches(value)) return null
        return value
    }

    private fun payloadKeys(json: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys += iterator.next()
        return keys
    }

    private fun strictPositiveInt(json: JSONObject, key: String): Long {
        val value = json.opt(key) as? Number
            ?: throw protocol()
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw protocol()
        }
        if (decimal.signum() <= 0 || decimal.stripTrailingZeros().scale() > 0) {
            throw protocol()
        }
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            throw protocol()
        }
    }

    /**
     * Bounds the one dynamic server string that reaches the UI as recovery
     * text. It is still preserved without trimming/escaping, but a value that
     * is too long or carries control characters other than the usual whitespace
     * (CR/LF/TAB) is dropped (null) instead of being rendered/composed.
     */
    private fun safePendingSteer(value: String?): String? {
        if (value == null || value.isBlank()) return null
        if (value.length > MAX_PENDING_STEER_LENGTH) return null
        val hasDisallowedControl = value.any { character ->
            (character.code < 0x20 && character != '\n' && character != '\t' && character != '\r') ||
                character.code == 0x7F
        }
        return if (hasDisallowedControl) null else value
    }

    private fun protocol(): ChatTurnProtocolException = ChatTurnProtocolException()

    private companion object {
        const val FIRST_SEQUENCE = 1L
        const val MAX_ID_LENGTH = 256
        const val MAX_DELTA_LENGTH = 32 * 1024
        const val MAX_COMPOSED_LENGTH = 1024 * 1024
        const val MAX_TOOL_NAME_LENGTH = 80
        const val MAX_PENDING_STEER_LENGTH = 32 * 1024
        const val SEEN_SEQS_WINDOW = 64

        const val SESSION_ID_FIELD = "session_id"
        const val RUN_ID_FIELD = "run_id"
        const val SEQ_FIELD = "seq"
        const val TS_FIELD = "ts"
        const val MESSAGE_FIELD = "message"
        const val MESSAGE_ID_FIELD = "message_id"
        const val NESTED_MESSAGE_ID_FIELD = "id"
        const val ROLE_FIELD = "role"
        const val DELTA_FIELD = "delta"
        const val CONTENT_FIELD = "content"
        const val PENDING_STEER_FIELD = "pending_steer"
        const val REQUEST_ID_FIELD = "request_id"
        const val DESCRIPTION_FIELD = "description"
        const val CHOICES_FIELD = "choices"
        const val APPROVAL_ONCE = "once"
        const val APPROVAL_DENY = "deny"
        const val TOOL_NAME_FIELD = "tool_name"
        const val ROLE_ASSISTANT = "assistant"

        const val RUN_STARTED = "run.started"
        const val MESSAGE_STARTED = "message.started"
        const val ASSISTANT_DELTA = "assistant.delta"
        const val ASSISTANT_COMPLETED = "assistant.completed"
        const val TOOL_STARTED = "tool.started"
        const val TOOL_COMPLETED = "tool.completed"
        const val TOOL_FAILED = "tool.failed"
        const val TOOL_PROGRESS = "tool.progress"
        const val REASONING_STARTED = "reasoning.started"
        const val REASONING_DELTA = "reasoning.delta"
        const val REASONING_COMPLETED = "reasoning.completed"
        const val RUN_COMPLETED = "run.completed"
        const val APPROVAL_REQUEST = "approval.request"
        const val DONE = "done"
        const val ERROR = "error"
        val APPROVAL_V1_FIELDS = setOf(
            SESSION_ID_FIELD,
            RUN_ID_FIELD,
            SEQ_FIELD,
            TS_FIELD,
            REQUEST_ID_FIELD,
            DESCRIPTION_FIELD,
            CHOICES_FIELD
        )
        val TOOL_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_-]*")
        val LONG_MAX_DECIMAL: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE)
    }
}

/**
 * Closed protocol violation of the streaming turn. Always maps to
 * [ChatStreamError.IncompatibleApi]; carries no dynamic data.
 */
class ChatTurnProtocolException : Exception()
