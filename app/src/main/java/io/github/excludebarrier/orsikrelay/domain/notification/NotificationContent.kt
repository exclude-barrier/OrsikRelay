package io.github.excludebarrier.orsikrelay.domain.notification

/**
 * The two kinds of local notification this app can raise. Both are
 * local-only: they are produced from the already-running in-process chat
 * stream and never involve a push, cloud relay or any remote channel.
 */
enum class NotificationType {
    /** Hermes is waiting for a mobile approval decision on a run. */
    APPROVAL,

    /** A user-started run finished while its chat was not being viewed. */
    RUN_COMPLETED
}

/**
 * Closed, lockscreen-safe routing content for one local notification.
 *
 * Only safe, navigation-identifying data crosses this boundary:
 * - [title] is a validated, short, printable display label (the server
 *   profile label, or a fixed fallback); it is never a secret, command,
 *   path, URL or tool output;
 * - [profileId] and [sessionId] are the routing identifiers used to build
 *   the tap PendingIntent and to navigate back to the exact session. They
 *   are validated by the caller and are NOT shown in the notification text;
 * - the human-readable body text is supplied by the platform adapter from
 *   localized resources, chosen by [type], so no dynamic server text ever
 *   appears in the notification.
 *
 * Nothing here is derived from the untrusted wire beyond the closed,
 * validated [io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest] routing IDs,
 * whose format is enforced by that model.
 */
data class NotificationContent(
    val type: NotificationType,
    val title: String,
    val profileId: String,
    val sessionId: String
) {
    companion object {
        /**
         * A display label is safe to show in a notification title exactly
         * when it is a short, non-empty, printable string with no control
         * characters and no characters outside a conservative label set.
         * Anything else is rejected so the fixed fallback is used instead.
         */
        fun isSafeTitle(value: String): Boolean {
            if (value.isEmpty() || value.length > MAX_TITLE_LENGTH) return false
            return value.all { ch ->
                ch.isLetterOrDigit() ||
                    ch.isWhitespace() ||
                    ch in ALLOWED_TITLE_PUNCTUATION
            }
        }

        private const val MAX_TITLE_LENGTH = 40
        private val ALLOWED_TITLE_PUNCTUATION = " ._-/()[]"
    }
}
