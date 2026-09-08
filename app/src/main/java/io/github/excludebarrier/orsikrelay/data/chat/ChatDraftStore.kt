package io.github.excludebarrier.orsikrelay.data.chat

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Synchronous, app-private storage seam for the ordinary chat composer
 * draft (Mission 009A).
 *
 * One draft per (profileId, sessionId) pair. The seam is intentionally
 * minimal: the UI never sees this type — it depends on
 * [ChatDraftRepository], which wraps this store and adds the debounced,
 * latest-value write strategy. The composite key is collision-resistant
 * (see [chatDraftKey]), so IDs containing reserved or delimiter characters
 * can never bleed across profiles or sessions.
 *
 * Implementations must stay app-private, must fail with the closed
 * [ChatDraftStoreException] (no dynamic detail), and must never log the
 * draft text.
 */
interface ChatDraftStore {
    /**
     * Returns the exact stored draft for the pair, or null when no draft is
     * stored. An empty draft is never retained: [delete] removes the record.
     */
    fun load(profileId: String, sessionId: String): String?

    /** Stores the exact [text] for the pair, replacing any previous value. */
    fun save(profileId: String, sessionId: String, text: String)

    /**
     * Removes the stored draft for the pair. Idempotent: deleting an
     * already-absent pair succeeds silently.
     */
    fun delete(profileId: String, sessionId: String)
}

/**
 * Builds a collision-resistant storage key for one (profileId, sessionId)
 * pair.
 *
 * Both components are base64url-encoded (no padding) and joined with a
 * single `.`. Because the encoding alphabet never contains `.`, the boundary
 * is unambiguous, and each side is injective, so two distinct pairs always
 * produce two distinct keys — even when an ID itself contains `.`, `-`,
 * `+`, whitespace or other reserved characters. The result is a plain
 * `.`-joined token that is safe to use as a SharedPreferences key (no raw
 * control characters).
 */
internal fun chatDraftKey(profileId: String, sessionId: String): String =
    encodeKeyComponent(profileId) + "." + encodeKeyComponent(sessionId)

private fun encodeKeyComponent(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
