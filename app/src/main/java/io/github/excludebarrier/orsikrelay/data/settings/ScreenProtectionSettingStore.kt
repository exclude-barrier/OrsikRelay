package io.github.excludebarrier.orsikrelay.data.settings

/** Local, non-sensitive persistence boundary for the screen-protection switch. */
interface ScreenProtectionSettingStore {
    fun read(): Boolean
    fun write(enabled: Boolean)
}

/**
 * Preference-backed store over a string read/write pair.
 *
 * Keeping the platform access behind two functions lets the default and
 * error handling run as plain JVM tests; [SharedPreferencesScreenProtectionSettingStore]
 * supplies the real preferences.
 */
internal class PreferenceScreenProtectionSettingStore(
    private val readStoredValue: () -> String?,
    private val writeStoredValue: (String) -> Unit
) : ScreenProtectionSettingStore {

    override fun read(): Boolean = try {
        readStoredValue()?.toBooleanStrictOrNull() ?: false
    } catch (_: Exception) {
        false
    }

    override fun write(enabled: Boolean) {
        try {
            writeStoredValue(enabled.toString())
        } catch (_: Exception) {
            // A failed write must never break the settings screen.
        }
    }
}
