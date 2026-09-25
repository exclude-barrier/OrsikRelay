package io.github.excludebarrier.orsikrelay.platform.run

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter

/**
 * Production [RunForegroundServiceStarter]: starts and stops the single
 * [RunForegroundService]. The start is a plain `startForegroundService`
 * call; it is always invoked from the foreground (the user's send action),
 * so the Android 11+ background-start restriction never applies. A failed
 * start degrades to no foreground state — the run itself continues in the
 * current process and simply does not get background continuity.
 */
class AndroidRunForegroundServiceStarter(
    private val context: Context
) : RunForegroundServiceStarter {

    override fun start(profileId: String, sessionId: String) {
        val intent = Intent(context, RunForegroundService::class.java).apply {
            putExtra(RunForegroundService.EXTRA_PROFILE_ID, profileId)
            putExtra(RunForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Exception) {
            // No foreground state; the run is unaffected.
        }
    }

    override fun stop() {
        context.stopService(Intent(context, RunForegroundService::class.java))
    }
}
