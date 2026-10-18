package io.github.excludebarrier.orsikrelay.ui.sessions

import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import java.util.Locale

/**
 * Pure, local title search over the already loaded session list.
 *
 * The bridge contract only offers `limit` and `offset` for the session list,
 * so searching never issues a request: it filters the sessions that are
 * already in memory. The server order is preserved and sessions without a
 * title never match.
 */
object SessionSearch {

    /**
     * Sessions whose title contains [query], case-insensitive in [locale].
     *
     * A blank [query] returns [sessions] unchanged.
     */
    fun filter(
        sessions: List<HermesSession>,
        query: String,
        locale: Locale
    ): List<HermesSession> {
        val needle = query.trim()
        if (needle.isEmpty()) return sessions
        val loweredNeedle = needle.lowercase(locale)
        return sessions.filter { session ->
            val title = session.title ?: return@filter false
            title.lowercase(locale).contains(loweredNeedle)
        }
    }

    /**
     * Trimmed query for the empty-result text, shortened to [maxLength]
     * characters plus an ellipsis when it is longer.
     */
    fun displayQuery(query: String, maxLength: Int = MAX_DISPLAY_LENGTH): String {
        val trimmed = query.trim()
        return if (trimmed.length <= maxLength) {
            trimmed
        } else {
            trimmed.take(maxLength) + "…"
        }
    }

    private const val MAX_DISPLAY_LENGTH = 40
}
