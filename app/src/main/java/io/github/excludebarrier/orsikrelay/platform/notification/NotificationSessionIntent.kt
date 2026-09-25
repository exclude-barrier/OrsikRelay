package io.github.excludebarrier.orsikrelay.platform.notification

import android.content.Context
import android.content.Intent
import io.github.excludebarrier.orsikrelay.MainActivity

/**
 * The closed intent contract for a notification tap (P3). It is the single
 * place that builds the PendingIntent and that parses/validates the intent on
 * receipt, so the notification side and the activity side can never drift.
 *
 * Security:
 * - the intent targets the app's own [MainActivity] by an explicit component
 *   (never an implicit broadcast), with a fixed action and only two safe
 *   extras;
 * - only the routing identifiers (profile ID, session ID) cross the intent
 *   boundary; no credential, token, command, path or server payload is ever
 *   placed in an extra;
 * - on receipt the action and both extras are re-validated against a strict,
 *   closed ID shape; anything malformed is rejected (fail-closed) so a forged
 *   or corrupt intent can never drive navigation.
 */
object NotificationSessionIntent {
    const val ACTION_OPEN_SESSION = "io.github.excludebarrier.orsikrelay.action.OPEN_SESSION"
    const val EXTRA_PROFILE_ID = "io.github.excludebarrier.orsikrelay.extra.PROFILE_ID"
    const val EXTRA_SESSION_ID = "io.github.excludebarrier.orsikrelay.extra.SESSION_ID"

    /**
     * Builds the explicit, self-contained intent that a notification's
     * PendingIntent should fire. [profileId] and [sessionId] must already be
     * safe routing identifiers; they are stored verbatim and re-validated on
     * receipt.
     */
    fun buildIntent(context: Context, profileId: String, sessionId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION
            putExtra(EXTRA_PROFILE_ID, profileId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }

    /**
     * Parses and validates a notification-tap intent. Returns the (validated)
     * routing IDs, or null when the action, component or any extra is missing
     * or malformed. The component is checked so an intent that merely copies
     * the action from another app is still rejected.
     */
    fun parse(intent: Intent): Pair<String, String>? {
        if (intent.action != ACTION_OPEN_SESSION) return null
        val component = intent.component ?: return null
        if (component.className != MainActivity::class.java.name) return null
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return null
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
        if (!isSafeId(profileId) || !isSafeId(sessionId)) return null
        return profileId to sessionId
    }

    /**
     * A routing identifier is safe exactly when it is a short string made only
     * of a conservative ID character set. This is intentionally strict and
     * closed: it is a fail-closed gate, not a permissive matcher.
     */
    internal fun isSafeId(value: String): Boolean = SAFE_ID.matches(value)

    private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,128}")
}
