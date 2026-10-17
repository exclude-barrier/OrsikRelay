package io.github.excludebarrier.orsikrelay.data.notification

/**
 * Tracks whether the [android.Manifest.permission.POST_NOTIFICATIONS]
 * permission has already been requested of the user. This is the "ask at
 * most once" flag: it prevents re-asking after the user has made a choice
 * (granted, denied, or dismissed the justification), so the app never spams.
 * It stores no credential or content — only a single boolean.
 */
interface NotificationPermissionStore {
    val hasRequested: Boolean
    fun markRequested()
}
