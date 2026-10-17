package io.github.excludebarrier.orsikrelay.domain.notification

/**
 * Pure, Android-free notification policy.
 *
 * The decision is total and side-effect free so it is directly unit-testable
 * without any Android, lifecycle or stream machinery. The single rule is:
 * notify only when the app is NOT in the foreground (the user is not
 * actively looking) AND this exact terminal event has not already produced a
 * notification for this process lifetime.
 *
 * Foreground/background semantics (documented scope):
 * - the app process is alive but not visible (home button / app switch):
 *   the in-process chat stream keeps running, so a terminal event can still
 *   arrive and a notification is posted;
 * - the app process is destroyed (swiped away / killed): no in-process event
 *   can arrive and therefore no notification is posted; this is a documented
 *   limitation because the app intentionally has no foreground service or
 *   push channel;
 * - the app is visible on the SAME chat: the user is watching, so no
 *   notification;
 * - the app is visible on a DIFFERENT session while a run streams in another:
 *   the current architecture disposes the other session's collector on
 *   navigation, so its stream stops and no event arrives; this is also a
 *   documented limitation and is intentionally NOT worked around here.
 */
object NotificationPolicy {

    /**
     * Returns true only when a notification should be posted.
     *
     * @param isForeground whether the app process is currently in the
     *   foreground (visible). A notification is posted only when false.
     * @param alreadyNotified whether this exact terminal event already
     *   produced a notification in this process lifetime. A duplicate is
     *   never posted.
     */
    fun shouldNotify(isForeground: Boolean, alreadyNotified: Boolean): Boolean =
        !isForeground && !alreadyNotified
}
