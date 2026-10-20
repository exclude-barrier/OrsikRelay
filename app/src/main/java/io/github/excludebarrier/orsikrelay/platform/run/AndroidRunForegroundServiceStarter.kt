package io.github.excludebarrier.orsikrelay.platform.run

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.platform.notification.AppForegroundTracker

/**
 * Production [RunForegroundServiceStarter]: starts and stops the single
 * [RunForegroundService]. The start is a plain `startForegroundService`
 * call from the user's send action. A send with an attachment starts it only
 * after the image was read, and the user may have left the app by then:
 * Android 12+ refuses a foreground-service start from the background. Such a
 * refused start is remembered and retried as soon as the app is visible
 * again; [stop] (the run ended) drops it. The run itself is never affected.
 */
class AndroidRunForegroundServiceStarter(
    private val context: Context
) : RunForegroundServiceStarter {

    @Volatile
    private var pendingStart: Pair<String, String>? = null

    init {
        AppForegroundTracker.addForegroundListener(::retryPendingStart)
    }

    override fun start(profileId: String, sessionId: String) {
        pendingStart = if (tryStart(profileId, sessionId)) null else profileId to sessionId
    }

    private fun retryPendingStart() {
        val (profileId, sessionId) = pendingStart ?: return
        pendingStart = if (tryStart(profileId, sessionId)) null else profileId to sessionId
    }

    private fun tryStart(profileId: String, sessionId: String): Boolean {
        val intent = Intent(context, RunForegroundService::class.java).apply {
            putExtra(RunForegroundService.EXTRA_PROFILE_ID, profileId)
            putExtra(RunForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (error: Exception) {
            false
        }
    }

    override fun stop() {
        pendingStart = null
        context.stopService(Intent(context, RunForegroundService::class.java))
    }
}
