package io.github.excludebarrier.orsikrelay.data.notification

import android.content.Context

/**
 * App-private [SharedPreferences] backing for the "ask at most once" flag (P3).
 * Only a single boolean is stored; no credential, URL or notification content
 * ever touches this store.
 */
class SharedPreferencesNotificationPermissionStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : NotificationPermissionStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    override val hasRequested: Boolean
        get() = preferences.getBoolean(PREFERENCES_KEY, false)

    override fun markRequested() {
        preferences.edit().putBoolean(PREFERENCES_KEY, true).apply()
    }

    companion object {
        private const val PREFERENCES_KEY = "notification_permission_requested"
        private const val PREFERENCES_NAME = "orsik_relay_notifications"
    }
}
