package io.github.excludebarrier.orsikrelay.network

/**
 * Incremental parser for the confirmed Hermes SSE framing (Mission
 * 006A).
 *
 * The server writes frames as
 *
 * ```text
 * event: <name>
 * data: <JSON object>
 * <empty line>
 * ```
 *
 * plus optional keepalive comment lines (`: keepalive`) that are
 * silently skipped.
 *
 * Rules implemented here:
 * - LF, CRLF and lone CR line breaks are accepted; a CRLF pair is one
 *   single line break even if the two bytes are split across two
 *   `feed` chunks (`\r` at the end of one chunk, `\n` at the start of
 *   the next). A `\r` not immediately followed by `\n` is a lone CR
 *   line break on its own; it never swallows a later `\n`;
 * - a field line is `name:value` with exactly one optional leading
 *   space after the colon;
 * - several `data:` lines of one frame are joined with `\n` (SSE
 *   semantics);
 * - the confirmed framing carries exactly one `event:` field per
 *   frame; a second `event:` field is ambiguous and rejected closed;
 * - an empty line dispatches the buffered frame and clears it;
 * - a single logical line must not exceed [maxLineBytes] UTF-8 bytes
 *   (multi-byte characters count their real encoded size) and the data
 *   payload of one frame must not exceed [maxFrameBytes]: the payload
 *   is the sum of the exact UTF-8 sizes of the `data:` values plus
 *   exactly one `\n` separator between `N` data lines (`N - 1`
 *   bytes);
 * - each line is buffered at most [maxLineBytes] UTF-16 code units so
 *   the buffer itself stays bounded, and the exact UTF-8 size is
 *   checked once the line is complete; unpaired UTF-16 surrogates are
 *   rejected closed.
 * - no raw line or payload ever escapes this class; only the closed
 *   [ParsedSseFrame] is returned.
 *
 * The parser is stateful per connection; create one instance per
 * stream. It is not thread-safe: feed it from a single reader thread.
 */
class SseFrameParser(
    private val maxLineBytes: Int = 64 * 1024,
    private val maxFrameBytes: Int = 256 * 1024,
    private val terminalEventName: String? = null
) {
    private val line = StringBuilder()
    private var lineCodeUnits = 0
    private var eventName: String? = null
    private var eventNameSet = false
    private val dataLines = ArrayList<String>()
    private var dataBytes = 0
    // Once a terminal frame has been dispatched, every further line of
    // the stream is semantically outside the turn and must be ignored -
    // it must never raise a framing violation, no matter how malformed.
    private var terminated = false
    // A `\r` at the very end of a chunk may still be the first half of
    // a CRLF pair whose `\n` opens the next chunk. The line is
    // finalized immediately; the flag only records that the first
    // character of the next chunk, if it is a `\n`, still belongs to
    // the same CRLF pair and must be consumed instead of acting as an
    // additional (empty) line break.
    private var pendingLineBreak = false

    /**
     * Feeds one chunk of UTF-8 decoded text into the parser and returns
     * all fully received frames.
     *
     * @throws SseFrameParserException on any framing violation or size
     * limit overflow.
     */
    fun feed(chunk: String): List<ParsedSseFrame> {
        if (terminated) {
            return emptyList()
        }
        val frames = ArrayList<ParsedSseFrame>()
        var index = 0
        if (pendingLineBreak && chunk.isNotEmpty()) {
            // The previous chunk ended with a `\r` whose line was already
            // finalized. Only the very first character of this chunk can
            // still decide: a `\n` is the second half of a CRLF pair (one
            // line break in total, no empty line in between) and is
            // consumed here; any other character proves the `\r` was a
            // lone CR and the flag is discarded. An empty chunk leaves
            // the decision open.
            if (chunk[0] == '\n') {
                index = 1
            }
            pendingLineBreak = false
        }
        while (index < chunk.length) {
            val character = chunk[index]
            if (character == '\n') {
                index += 1
                processLine()?.let { frames += it }
                line.setLength(0)
                lineCodeUnits = 0
                if (terminated) {
                    break
                }
                continue
            }
            if (character == '\r') {
                index += 1
                if (index < chunk.length && chunk[index] == '\n') {
                    // Complete CRLF pair inside this chunk.
                    index += 1
                } else if (index < chunk.length) {
                    // Lone CR: a complete line break on its own. The
                    // next character (even a `\n`) starts the following
                    // line.
                } else {
                    // `\r` at the very end of the chunk: the line is not
                    // final until the next chunk decides whether a `\n`
                    // follows.
                    pendingLineBreak = true
                }
                processLine()?.let { frames += it }
                line.setLength(0)
                lineCodeUnits = 0
                if (terminated) {
                    break
                }
                continue
            }
            // Conservative bound: at most [maxLineBytes] code units per
            // line (one ASCII character per unit is the minimum). The
            // exact UTF-8 size is checked in [processLine] once the
            // line is complete.
            if (lineCodeUnits + 1 > maxLineBytes) {
                throw SseFrameParserException()
            }
            lineCodeUnits += 1
            line.append(character)
            index += 1
        }
        return frames
    }

    /**
     * Handles the end of the stream. A frame or line buffered without a
     * final empty line is rejected as incomplete framing.
     *
     * @throws SseFrameParserException when a partial frame is pending.
     */
    fun endOfStream() {
        if (terminated) {
            return
        }
        if (line.isNotEmpty() || eventName != null || dataLines.isNotEmpty()) {
            throw SseFrameParserException()
        }
    }

    /** Processes one complete line; returns the frame on a boundary. */
    private fun processLine(): ParsedSseFrame? {
        if (line.isEmpty()) {
            val frame = buildAndClearFrame()
            if (frame == null) {
                return null
            }
            // Dispatching the terminal frame closes the turn: everything
            // after it is ignored, never validated.
            if (terminalEventName != null && frame.eventName == terminalEventName) {
                terminated = true
            }
            return frame
        }
        val text = line.toString()
        val textBytes = utf8Bytes(text)
        if (textBytes < 0) {
            // Unpaired UTF-16 surrogate: not valid UTF-8.
            throw SseFrameParserException()
        }
        if (textBytes > maxLineBytes) {
            throw SseFrameParserException()
        }
        if (text.startsWith(":")) {
            // Comment / keepalive line: ignored.
            return null
        }
        val separator = text.indexOf(':')
        val field: String
        val value: String
        if (separator < 0) {
            field = text
            value = ""
        } else {
            field = text.substring(0, separator)
            value = text.substring(separator + 1).let {
                if (it.startsWith(" ")) it.substring(1) else it
            }
        }
        when (field) {
            "event" -> {
                if (eventNameSet) {
                    // The confirmed framing carries exactly one `event:`
                    // field per frame; a second one is ambiguous.
                    throw SseFrameParserException()
                }
                val valueBytes = utf8Bytes(value)
                if (valueBytes < 0 || valueBytes > MAX_FIELD_VALUE_BYTES) {
                    throw SseFrameParserException()
                }
                eventNameSet = true
                eventName = value
            }
            "data" -> {
                val bytes = utf8Bytes(value)
                // Payload = sum of the UTF-8 sizes of the data values
                // plus exactly one separator between N lines (N - 1
                // bytes). The first value costs only its bytes, the
                // following ones one separator byte each.
                val cost = if (bytes < 0) {
                    throw SseFrameParserException()
                } else if (dataLines.isEmpty()) {
                    bytes
                } else {
                    bytes + 1
                }
                if (dataBytes + cost > maxFrameBytes) {
                    throw SseFrameParserException()
                }
                dataBytes += cost
                dataLines += value
            }
            else -> {
                // Unknown field name (id, retry, ...): rejected closed.
                throw SseFrameParserException()
            }
        }
        return null
    }

    private fun buildAndClearFrame(): ParsedSseFrame? {
        val name = eventName
        val data = if (dataLines.isEmpty()) {
            null
        } else {
            dataLines.joinToString("\n")
        }
        eventName = null
        eventNameSet = false
        dataLines.clear()
        dataBytes = 0
        if (name == null && data == null) {
            return null
        }
        return ParsedSseFrame(name, data)
    }

    /**
     * Exact UTF-8 size of the given UTF-16 text in bytes. A surrogate
     * pair counts as the 4 bytes of its code point. Returns `-1` when
     * the text contains an unpaired surrogate, which cannot occur in
     * valid UTF-8.
     */
    private fun utf8Bytes(text: CharSequence): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when (character) {
                in '\u0000'..'\u007F' -> total += 1
                in '\u0080'..'\u07FF' -> total += 2
                in '\uD800'..'\uDBFF' -> {
                    // High surrogate: a complete pair needs the next
                    // low surrogate, together 4 UTF-8 bytes.
                    if (index + 1 >= text.length) {
                        return -1
                    }
                    val next = text[index + 1]
                    if (next !in '\uDC00'..'\uDFFF') {
                        return -1
                    }
                    total += 4
                    index += 1
                }
                in '\uDC00'..'\uDFFF' -> {
                    // Unpaired low surrogate.
                    return -1
                }
                else -> total += 3
            }
            index += 1
        }
        return total
    }

    private companion object {
        const val MAX_FIELD_VALUE_BYTES = 4096
    }
}

/**
 * One fully received SSE frame: the optional event name and the joined
 * data payload. Pure comment frames are never returned.
 */
data class ParsedSseFrame(
    val eventName: String?,
    val data: String?
)

/**
 * Closed framing violation: size limit overflow, unknown field,
 * duplicate `event` field, unpaired surrogate, or an incomplete frame
 * at end of stream. Carries no dynamic data.
 */
class SseFrameParserException : Exception()
