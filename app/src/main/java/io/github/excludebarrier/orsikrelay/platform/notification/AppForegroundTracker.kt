package io.github.excludebarrier.orsikrelay.platform.notification

import io.github.excludebarrier.orsikrelay.domain.notification.AppForegroundState

/**
 * Production [AppForegroundState] for the single-activity app. It tracks
 * the started-activity count: >0 means the app is in the foreground (visible),
 * 0 means it is in the background (home button / app switch) but the process
 * is still alive.
 *
 * It is driven by the [MainActivity] start/stop callbacks and read (via a
 * lambda) by the [io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator].
 * It intentionally does NOT distinguish "process destroyed": that state is not
 * reachable here because once the process is gone, no code in this object runs
 * (a documented limitation, see the policy KDoc).
 */
object AppForegroundTracker : AppForegroundState {
    @Volatile
    private var startedActivities = 0

    override val isForeground: Boolean
        get() = startedActivities > 0

    fun activityStarted() {
        startedActivities += 1
    }

    fun activityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }
}
