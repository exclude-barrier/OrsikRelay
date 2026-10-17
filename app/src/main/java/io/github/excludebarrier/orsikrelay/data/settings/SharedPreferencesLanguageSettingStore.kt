package io.github.excludebarrier.orsikrelay.data.settings

import android.annotation.SuppressLint
import android.content.Context
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages

/**
 * Stores one supported language tag in app-private preferences.
 *
 * The versioned key and preferences file allow a later schema change without
 * misreading an older value. Absent, unreadable and unknown values all
 * resolve to System, so a corrupt preference never breaks startup.
 */
class SharedPreferencesLanguageSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : LanguageSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    override fun read(): String? = try {
        SupportedLanguages.normalizeTag(preferences.getString(PREFERENCES_KEY, null))
    } catch (_: Exception) {
        null
    }

    @SuppressLint("UseKtx")
    override fun write(tag: String?) {
        val normalized = SupportedLanguages.normalizeTag(tag)
        preferences.edit().apply {
            if (normalized == null) {
                remove(PREFERENCES_KEY)
            } else {
                putString(PREFERENCES_KEY, normalized)
            }
        }.apply()
    }

    companion object {
        internal const val PREFERENCES_KEY = "language_tag_v1"
        internal const val PREFERENCES_NAME = "orsik_relay_language"
    }
}
