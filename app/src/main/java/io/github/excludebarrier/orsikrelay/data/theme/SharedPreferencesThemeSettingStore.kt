package io.github.excludebarrier.orsikrelay.data.theme

import android.annotation.SuppressLint
import android.content.Context
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode

/** Stores one non-sensitive theme-mode name in app-private preferences. */
class SharedPreferencesThemeSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : ThemeSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    override fun read(): ThemeMode = ThemeMode.fromStored(
        preferences.getString(PREFERENCES_KEY, null)
    )

    @SuppressLint("UseKtx")
    override fun write(mode: ThemeMode) {
        preferences.edit().putString(PREFERENCES_KEY, mode.name).apply()
    }

    companion object {
        internal const val PREFERENCES_KEY = "theme_mode"
        internal const val PREFERENCES_NAME = "orsik_relay_appearance"
    }
}
