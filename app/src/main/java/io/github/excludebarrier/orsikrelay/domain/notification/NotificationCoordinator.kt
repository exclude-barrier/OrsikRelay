package io.github.excludebarrier.orsikrelay.domain.notification

import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest

/**
 * Domain decision layer that turns a terminal chat-stream event into an
 * optional local notification (P3).
 *
 * This class owns the notification policy application and the duplicate
 * guard; it never touches an Android notification API. It hands a closed,
 * lockscreen-safe [NotificationContent] to the injected [poster] (the Android
 * adapter) only when the policy allows it.
 *
 * Threading: it is driven from the chat view model's main-thread scope, so
 * the in-memory duplicate set is accessed on the main thread only. The
 * duplicate guard is the last line of defence against re-posting the same
 * terminal event; each terminal event is delivered by the stream exactly
 * once, so a set (not a persistent store) is sufficient for the documented
 * in-process scope.
 */
class NotificationCoordinator(
    private val poster: NotificationPoster,
    private val isForeground: () -> Boolean,
    private val serverNameProvider: (profileId: String) -> String,
    private val fallbackTitle: String
) {
    /**
     * Terminal events that already produced a notification this process
     * lifetime, keyed so an approval and a run completion for the same
     * session never collide and a second identical event is suppressed.
     */
    private val notifiedKeys = HashSet<String>()

    /**
     * Called when a valid, server-redacted [ApprovalRequest] arrives for the
     * active turn. Posts an approval notification only when the app is in the
     * background and this request has not already been notified.
     *
     * Only the closed routing IDs and a validated server label are used; the
     * request's command, arguments, path, URL and tool output are never read
     * or copied here.
     */
    fun onApprovalRequested(profileId: String, sessionId: String, request: ApprovalRequest) {
        val key = "approval|$profileId|$sessionId|${request.requestId}"
        if (notifiedKeys.contains(key)) return
        if (!NotificationPolicy.shouldNotify(isForeground(), alreadyNotified = false)) return
        notifiedKeys.add(key)
        poster.post(
            NotificationContent(
                type = NotificationType.APPROVAL,
                title = titleFor(profileId),
                profileId = profileId,
                sessionId = sessionId
            )
        )
    }

    /**
     * Called when a user-started run completes (terminal [ChatTurnEvent.TurnCompleted]).
     * Posts a run-completed notification only when the app is in the background
     * and this run has not already been notified.
     */
    fun onRunCompleted(profileId: String, sessionId: String, runId: String) {
        val key = "run|$profileId|$sessionId|$runId"
        if (notifiedKeys.contains(key)) return
        if (!NotificationPolicy.shouldNotify(isForeground(), alreadyNotified = false)) return
        notifiedKeys.add(key)
        poster.post(
            NotificationContent(
                type = NotificationType.RUN_COMPLETED,
                title = titleFor(profileId),
                profileId = profileId,
                sessionId = sessionId
            )
        )
    }

    /**
     * The validated server display label for the title, or the fixed fallback
     * when the provider returns anything that is not a safe, short, printable
     * label. This is the only dynamic text that can reach a notification, and
     * it is constrained to a conservative label set.
     */
    private fun titleFor(profileId: String): String {
        val name = runCatching { serverNameProvider(profileId) }.getOrNull().orEmpty()
        return if (NotificationContent.isSafeTitle(name)) name else fallbackTitle
    }
}
