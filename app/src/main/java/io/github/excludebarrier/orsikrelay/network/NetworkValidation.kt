package io.github.excludebarrier.orsikrelay.network

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Shared, fail-closed validation helpers for the one-shot network clients.
 *
 * The SSE stream path already decodes strictly and bounds every
 * server-derived string; these helpers bring the same discipline to the
 * one-shot JSON clients (sessions, messages, runs, devices, capabilities).
 */

/** Maximum length accepted for any short server-derived display string. */
internal const val MAX_SAFE_SERVER_STRING_LENGTH = 1024

private val CANONICAL_UUID = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)

/**
 * A canonical UUID only. `java.util.UUID.fromString` is lenient on Android
 * (it accepts e.g. `1-2-3-4-5`), which would let a malformed server value
 * through, so this checks the exact 8-4-4-4-12 hex form.
 */
internal fun isStrictUuid(value: String): Boolean =
    value.length == 36 && CANONICAL_UUID.matches(value)

/**
 * True when [value] is short enough and contains no ISO control character
 * ([U+0000..U+001F] or DEL). Such values reach the UI, so they must not carry
 * control bytes that could break layout or logs.
 */
internal fun isSafeServerString(
    value: String,
    maxLength: Int = MAX_SAFE_SERVER_STRING_LENGTH
): Boolean = value.length <= maxLength && value.none { it.code < 0x20 || it.code == 0x7F }

/**
 * Strict UTF-8 decode. Returns `null` for malformed or unmappable input
 * instead of silently substituting U+FFFD, so callers can fail closed.
 */
/**
 * Extracts `error.code` from the closed server error envelope
 * `{"error":{"message","type","code"}}`. Returns `null` for any other shape,
 * so callers can map only the documented code and fail closed otherwise.
 */
internal fun extractErrorCode(text: String): String? {
    val root = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    val error = root.opt("error") as? JSONObject ?: return null
    return error.opt("code") as? String
}

internal fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    null
}
