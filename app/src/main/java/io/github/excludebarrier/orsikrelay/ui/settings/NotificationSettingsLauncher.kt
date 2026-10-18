package io.github.excludebarrier.orsikrelay.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the Android notification settings for this app.
 *
 * Uses the per-app notification page (API 26+, which is the app's minSdk).
 * If no activity can handle it, it falls back to the app-details page and
 * never crashes when even that is unavailable.
 *
 * [start] is a test seam: the production default starts the intent on the
 * given context, tests can record the intents instead.
 */
internal fun openAppNotificationSettings(
    context: Context,
    start: (Intent) -> Unit = context::startActivity
) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .withNewTaskIfNeeded(context)
    try {
        start(notificationSettings)
    } catch (_: ActivityNotFoundException) {
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).withNewTaskIfNeeded(context)
        runCatching { start(appDetails) }
    }
}

private fun Intent.withNewTaskIfNeeded(context: Context): Intent {
    if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return this
}
