package io.github.excludebarrier.orsikrelay.data.settings

import android.annotation.SuppressLint
import android.content.Context

/**
 * Stores the Material You switch in app-private preferences.
 *
 * Absent, unknown or unreadable values resolve to `false` (the fixed brand
 * palette), so a corrupt preference never changes the look of the app on its
 * own.
 */
@SuppressLint("UseKtx")
class SharedPreferencesMaterialYouSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : MaterialYouSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    private val delegate = PreferenceMaterialYouSettingStore(
        readStoredValue = { preferences.getString(PREFERENCES_KEY, null) },
        writeStoredValue = { value ->
            preferences.edit().putString(PREFERENCES_KEY, value).apply()
        }
    )

    override fun read(): Boolean = delegate.read()

    override fun write(enabled: Boolean) = delegate.write(enabled)

    companion object {
        internal const val PREFERENCES_KEY = "material_you_v1"
        internal const val PREFERENCES_NAME = "orsik_relay_material_you"
    }
}
