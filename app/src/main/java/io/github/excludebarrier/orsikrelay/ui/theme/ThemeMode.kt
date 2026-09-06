package io.github.excludebarrier.orsikrelay.ui.theme

/**
 * User-selectable app theme mode.
 *
 * [System] (the default) follows the system light/dark setting. [Light] and
 * [Dark] force a fixed appearance independent of the system setting.
 *
 * [resolve] is the single place where a mode plus the current system dark
 * flag is mapped to the concrete dark/light decision passed to
 * [HermesTheme]. Keeping the mapping pure keeps it unit-testable without
 * Android or Compose.
 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    fun resolve(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        val DEFAULT: ThemeMode = System

        /**
         * Parses a persisted value. Unknown or absent values fall back to
         * [DEFAULT] so a corrupt or future preference can never break
         * startup.
         */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: DEFAULT
    }
}
