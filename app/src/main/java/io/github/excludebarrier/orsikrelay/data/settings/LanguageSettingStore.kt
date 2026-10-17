package io.github.excludebarrier.orsikrelay.data.settings

/**
 * Local, non-sensitive persistence boundary for the selected app language.
 *
 * A `null` value means "System" (follow the device language). Any other value
 * is a tag from [io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages].
 */
interface LanguageSettingStore {
    fun read(): String?
    fun write(tag: String?)
}
