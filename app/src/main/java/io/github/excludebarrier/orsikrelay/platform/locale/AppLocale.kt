package io.github.excludebarrier.orsikrelay.platform.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesLanguageSettingStore
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages
import java.util.Locale

/**
 * Applies the persisted language across the app.
 *
 * API 33+: [LocaleManager] is the platform mechanism. It localizes all app
 * resources, including services and notifications, and the framework
 * recreates the activity after a locale change.
 *
 * API 26–32: there is no app-locale API. The selected language is applied
 * through [Context.createConfigurationContext] in every component that
 * renders text ([io.github.excludebarrier.orsikrelay.MainActivity] and
 * [io.github.excludebarrier.orsikrelay.platform.run.RunForegroundService]),
 * and an explicit activity recreation applies a user change.
 */
object AppLocale {

    /** Wraps [base] with the persisted language on API < 33; otherwise unchanged. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = runCatching {
            SharedPreferencesLanguageSettingStore(base).read()
        }.getOrNull() ?: return base
        return localizedContext(base, tag)
    }

    /** A context configured for exactly [tag], independent of API level. */
    fun localizedContext(base: Context, tag: String): Context {
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(configuration)
    }

    /**
     * Applies a user selection. `null` means System.
     *
     * The activity is recreated explicitly on every API level so the new
     * language is visible immediately; this also covers devices where the
     * framework does not raise a configuration change for an app locale.
     * Existing recreation mechanics (running runs, drafts) are untouched.
     */
    fun applySelection(activity: Activity, tag: String?) {
        val normalized = SupportedLanguages.normalizeTag(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (normalized == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
            activity.getSystemService(LocaleManager::class.java)
                ?.setApplicationLocales(locales)
        }
        if (!activity.isFinishing) {
            activity.recreate()
        }
    }
}
