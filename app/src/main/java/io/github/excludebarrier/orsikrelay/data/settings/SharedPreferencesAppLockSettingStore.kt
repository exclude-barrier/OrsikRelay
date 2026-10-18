package io.github.excludebarrier.orsikrelay.data.settings

import android.annotation.SuppressLint
import android.content.Context

/**
 * Stores the app-lock switch in app-private preferences.
 *
 * Absent, unknown or unreadable values resolve to `false` (lock off), so a
 * corrupt preference never surprises the user by enabling a lock they did not
 * choose.
 */
@SuppressLint("UseKtx")
class SharedPreferencesAppLockSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : AppLockSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    private val delegate = PreferenceAppLockSettingStore(
        readStoredValue = { preferences.getString(PREFERENCES_KEY, null) },
        writeStoredValue = { value ->
            preferences.edit().putString(PREFERENCES_KEY, value).apply()
        }
    )

    override fun read(): Boolean = delegate.read()

    override fun write(enabled: Boolean) = delegate.write(enabled)

    companion object {
        internal const val PREFERENCES_KEY = "app_lock_v1"
        internal const val PREFERENCES_NAME = "orsik_relay_app_lock"
    }
}
