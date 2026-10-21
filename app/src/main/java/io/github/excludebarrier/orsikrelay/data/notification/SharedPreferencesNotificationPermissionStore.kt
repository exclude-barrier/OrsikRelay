package io.github.excludebarrier.orsikrelay.data.notification

import android.annotation.SuppressLint
import android.content.Context

/**
 * App-private [SharedPreferences] backing for the "ask at most once" flag.
 * Only a single boolean is stored; no credential, URL or notification content
 * ever touches this store.
 */
@SuppressLint("UseKtx")
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
        // Best-effort bookkeeping: never block the caller (the launcher
        // callback runs on the main thread) and never surface a failure.
        // apply() keeps the in-memory value immediately while the disk write
        // happens asynchronously.
        preferences.edit().putBoolean(PREFERENCES_KEY, true).apply()
    }

    companion object {
        private const val PREFERENCES_KEY = "notification_permission_requested"
        private const val PREFERENCES_NAME = "orsik_relay_notifications"
    }
}
