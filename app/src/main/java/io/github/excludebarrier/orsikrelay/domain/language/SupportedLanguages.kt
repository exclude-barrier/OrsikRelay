package io.github.excludebarrier.orsikrelay.domain.language

/**
 * One user-selectable app language.
 *
 * [tag] is the BCP 47 language tag used for the Android resource qualifier
 * (`values-<tag>`) and `locales_config.xml`; [displayName] is the language's
 * own name ("English", "Deutsch") and is deliberately not translated.
 */
data class SupportedLanguage(
    val tag: String,
    val displayName: String
)

/**
 * The single source of truth for the app languages.
 *
 * A new language is added here, plus a `values-<tag>/strings.xml` directory
 * and an entry in `res/xml/locales_config.xml`. The settings UI and the
 * language switch only read this list.
 */
object SupportedLanguages {

    /** Fallback language when the device language is not translated. */
    const val BASE_TAG: String = "en"

    val all: List<SupportedLanguage> = listOf(
        SupportedLanguage(tag = "en", displayName = "English"),
        SupportedLanguage(tag = "de", displayName = "Deutsch")
    )

    /** The base (English) language entry. */
    val base: SupportedLanguage = all.first { it.tag == BASE_TAG }

    /** Returns the supported language for [tag], or `null` for System/unknown. */
    fun fromTag(tag: String?): SupportedLanguage? {
        if (tag.isNullOrBlank()) return null
        return all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }

    /** Normalizes a stored tag to a supported tag; unknown values become System. */
    fun normalizeTag(tag: String?): String? = fromTag(tag)?.tag
}
