package io.github.excludebarrier.orsikrelay.security.applock

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Central app-lock state for the whole process.
 *
 * The controller starts LOCKED when the persisted switch is on (cold start)
 * and stays unlocked while the app is in the foreground. An activity stop
 * records a monotonic timestamp; the next start locks the app again once at
 * least [BACKGROUND_TIMEOUT_MILLIS] passed in between. A configuration change
 * (activity stop + immediate start) is well below that threshold, so rotation
 * never re-locks — and because the instance is process-scoped, the unlocked
 * state survives the recreation.
 *
 * [elapsedRealtime] is a test seam: production uses
 * [SystemClock.elapsedRealtime], JVM tests a mutable counter.
 */
@Stable
class AppLockController(
    initiallyEnabled: Boolean,
    private val persist: (Boolean) -> Unit,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val backgroundTimeoutMillis: Long = BACKGROUND_TIMEOUT_MILLIS
) {
    private var enabledState by mutableStateOf(initiallyEnabled)
    private var lockedState by mutableStateOf(initiallyEnabled)

    /** Timestamp of the last activity stop while the app was in the background. */
    private var backgroundedAt: Long? = null

    val enabled: Boolean
        get() = enabledState

    val locked: Boolean
        get() = lockedState

    /**
     * Changes the persisted switch. Turning the lock off also opens the app
     * immediately; turning it on never locks the current foreground session
     * (the switch itself required a successful authentication).
     */
    fun setEnabled(enabled: Boolean) {
        enabledState = enabled
        runCatching { persist(enabled) }
        if (!enabled) {
            lockedState = false
            backgroundedAt = null
        }
    }

    /** Records when the app left the foreground. */
    fun onActivityStopped() {
        backgroundedAt = elapsedRealtime()
    }

    /**
     * Re-evaluates the lock after the app returns to the foreground. The first
     * start of a fresh controller leaves the cold-start state untouched; only
     * a real background period can lock an already-open session.
     */
    fun onActivityStarted() {
        val stoppedAt = backgroundedAt ?: return
        backgroundedAt = null
        if (enabledState &&
            elapsedRealtime() - stoppedAt >= backgroundTimeoutMillis
        ) {
            lockedState = true
        }
    }

    /** Opens the app after a successful authentication. */
    fun unlock() {
        lockedState = false
    }

    companion object {
        const val BACKGROUND_TIMEOUT_MILLIS = 30_000L
    }
}
