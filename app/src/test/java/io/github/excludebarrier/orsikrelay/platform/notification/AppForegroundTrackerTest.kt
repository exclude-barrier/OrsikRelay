package io.github.excludebarrier.orsikrelay.platform.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class AppForegroundTrackerTest {

    @Test
    fun listenersRunOnlyWhenTheAppBecomesVisible() {
        var calls = 0
        AppForegroundTracker.addForegroundListener { calls += 1 }
        // Bring the tracker to a known background state.
        repeat(5) { AppForegroundTracker.activityStopped() }

        AppForegroundTracker.activityStarted()
        assertEquals("background -> visible notifies", 1, calls)
        AppForegroundTracker.activityStarted()
        assertEquals("a second visible activity does not", 1, calls)

        AppForegroundTracker.activityStopped()
        AppForegroundTracker.activityStopped()
        AppForegroundTracker.activityStarted()
        assertEquals("visible again after background notifies again", 2, calls)
        AppForegroundTracker.activityStopped()
    }
}
