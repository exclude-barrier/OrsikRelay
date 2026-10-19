package io.github.excludebarrier.orsikrelay.platform.notification

import android.content.Context
import android.content.Intent
import io.github.excludebarrier.orsikrelay.MainActivity
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The closed intent contract for a notification tap. It is the single
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
 *   or corrupt intent can never drive navigation;
 * - [MainActivity] is exported (launcher, pairing link), so any app could
 *   send it this action with its explicit component. Every intent the app
 *   builds therefore carries a random per-install tap token kept in private
 *   preferences; an intent without the matching token is ignored, so another
 *   app cannot open a chat or switch the active server.
 */
object NotificationSessionIntent {
    const val ACTION_OPEN_SESSION = "io.github.excludebarrier.orsikrelay.action.OPEN_SESSION"
    const val EXTRA_PROFILE_ID = "io.github.excludebarrier.orsikrelay.extra.PROFILE_ID"
    const val EXTRA_SESSION_ID = "io.github.excludebarrier.orsikrelay.extra.SESSION_ID"
    const val EXTRA_TAP_TOKEN = "io.github.excludebarrier.orsikrelay.extra.TAP_TOKEN"

    private const val PREFERENCES_NAME = "notification_tap"
    private const val KEY_TOKEN = "token"

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
            putExtra(EXTRA_TAP_TOKEN, tapToken(context))
        }

    /**
     * The per-install tap token, created on first use. A small private
     * preference read; it survives process death, so a notification posted
     * before a restart still opens its chat.
     */
    @Synchronized
    fun tapToken(context: Context): String {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString(separator = "") { "%02x".format(it) }
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Constant-time comparison; a missing value never matches. */
    internal fun tokenMatches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrEmpty() || actual.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }

    /**
     * Parses and validates a notification-tap intent. Returns the (validated)
     * routing IDs, or null when the action, component or any extra is missing
     * or malformed. The component is checked so an intent that merely copies
     * the action from another app is still rejected.
     */
    fun parse(intent: Intent, expectedToken: String): Pair<String, String>? {
        if (intent.action != ACTION_OPEN_SESSION) return null
        if (!tokenMatches(expectedToken, intent.getStringExtra(EXTRA_TAP_TOKEN))) return null
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

    private const val TOKEN_BYTES = 16
}
