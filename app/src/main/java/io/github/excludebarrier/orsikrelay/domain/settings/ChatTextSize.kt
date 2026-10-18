package io.github.excludebarrier.orsikrelay.domain.settings

/**
 * User-selectable text size for chat message content.
 *
 * [scale] multiplies the system font scale and is applied only to the
 * messages in a chat — never to labels, the composer or other screens.
 */
enum class ChatTextSize(val scale: Float) {
    Small(0.9f),
    Normal(1.0f),
    Large(1.15f);

    companion object {
        val DEFAULT: ChatTextSize = Normal

        /**
         * Parses a persisted value. Absent, unknown or corrupt values fall
         * back to [DEFAULT].
         */
        fun fromStored(value: String?): ChatTextSize = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: DEFAULT
    }
}
