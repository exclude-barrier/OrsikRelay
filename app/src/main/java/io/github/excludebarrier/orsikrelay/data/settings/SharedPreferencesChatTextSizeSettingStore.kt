package io.github.excludebarrier.orsikrelay.data.settings

import android.annotation.SuppressLint
import android.content.Context
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize

/**
 * Stores the chat text size in app-private preferences.
 *
 * The versioned key and preferences file allow a later schema change without
 * misreading an older value. Absent or unknown values resolve to
 * [ChatTextSize.DEFAULT]; a failed read never breaks startup.
 */
@SuppressLint("UseKtx")
class SharedPreferencesChatTextSizeSettingStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : ChatTextSizeSettingStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    private val delegate = PreferenceChatTextSizeSettingStore(
        readStoredValue = { preferences.getString(PREFERENCES_KEY, null) },
        writeStoredValue = { value ->
            preferences.edit().putString(PREFERENCES_KEY, value).apply()
        }
    )

    override fun read(): ChatTextSize = delegate.read()

    override fun write(size: ChatTextSize) = delegate.write(size)

    companion object {
        internal const val PREFERENCES_KEY = "chat_text_size_v1"
        internal const val PREFERENCES_NAME = "orsik_relay_chat_text_size"
    }
}
