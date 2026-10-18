package io.github.excludebarrier.orsikrelay.data.settings

import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize

/** Local, non-sensitive persistence boundary for the chat text size. */
interface ChatTextSizeSettingStore {
    fun read(): ChatTextSize
    fun write(size: ChatTextSize)
}

/**
 * Preference-backed store over a string read/write pair.
 *
 * Keeping the platform access behind two functions lets the default and
 * error handling run as plain JVM tests; [SharedPreferencesChatTextSizeSettingStore]
 * supplies the real preferences.
 */
internal class PreferenceChatTextSizeSettingStore(
    private val readStoredValue: () -> String?,
    private val writeStoredValue: (String) -> Unit
) : ChatTextSizeSettingStore {

    override fun read(): ChatTextSize = try {
        ChatTextSize.fromStored(readStoredValue())
    } catch (_: Exception) {
        ChatTextSize.DEFAULT
    }

    override fun write(size: ChatTextSize) {
        try {
            writeStoredValue(size.name)
        } catch (_: Exception) {
            // A failed write must never break the settings screen.
        }
    }
}
