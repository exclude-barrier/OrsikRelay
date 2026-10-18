package io.github.excludebarrier.orsikrelay.data.settings

import android.annotation.SuppressLint
import android.content.Context

/**
 * Stores the screen-protection switch in app-private preferences.
 *
 * Absent, unknown or unreadable values resolve to `false` (protection off),
 * so a corrupt preference never surprises the user by enabling a flag they
 * did not choose.
 */
@SuppressLint("UseKtx")
class SharedPreferencesScreenProtectionSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : ScreenProtectionSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    private val delegate = PreferenceScreenProtectionSettingStore(
        readStoredValue = { preferences.getString(PREFERENCES_KEY, null) },
        writeStoredValue = { value ->
            preferences.edit().putString(PREFERENCES_KEY, value).apply()
        }
    )

    override fun read(): Boolean = delegate.read()

    override fun write(enabled: Boolean) = delegate.write(enabled)

    companion object {
        internal const val PREFERENCES_KEY = "screen_protection_v1"
        internal const val PREFERENCES_NAME = "orsik_relay_screen_protection"
    }
}
