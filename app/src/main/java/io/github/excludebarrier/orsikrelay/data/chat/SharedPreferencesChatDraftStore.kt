package io.github.excludebarrier.orsikrelay.data.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException

/**
 * App-private [ChatDraftStore] on top of [SharedPreferences] (Mission 009A).
 *
 * This follows the repository's established persistence pattern —
 * [io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore]
 * and [io.github.excludebarrier.orsikrelay.data.theme.SharedPreferencesThemeSettingStore]
 * — rather than introducing DataStore/Room, which are not present in this
 * project's dependencies. One versioned, collision-resistant key per
 * (profileId, sessionId); the value is the exact draft text. An empty draft
 * is never retained: [delete] removes the record, so no empty entry lingers.
 *
 * All operations use `commit()` (durable, synchronous on the calling
 * thread) and are meant to run off the main thread via the repository's
 * `withContext(Dispatchers.IO)`. Every failure maps to the closed
 * [ChatDraftStoreException]; the draft text never reaches an error, a log or
 * a message.
 */
@SuppressLint("UseKtx")
class SharedPreferencesChatDraftStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : ChatDraftStore {

    private val preferences: SharedPreferences

    init {
        if (preferencesName.isBlank()) throw ChatDraftStoreException()
        preferences = try {
            context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
        } catch (_: Exception) {
            throw ChatDraftStoreException()
        }
    }

    override fun load(profileId: String, sessionId: String): String? = call {
        preferences.getString(draftKey(profileId, sessionId), null)
    }

    override fun save(profileId: String, sessionId: String, text: String) {
        call {
            if (!preferences.edit().putString(draftKey(profileId, sessionId), text).commit()) {
                throw ChatDraftStoreException()
            }
        }
    }

    override fun delete(profileId: String, sessionId: String) {
        call {
            // Idempotent: removing an absent key still commits successfully.
            if (!preferences.edit().remove(draftKey(profileId, sessionId)).commit()) {
                throw ChatDraftStoreException()
            }
        }
    }

    private fun draftKey(profileId: String, sessionId: String): String =
        KEY_PREFIX + chatDraftKey(profileId, sessionId)

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        // Never swallow cancellation: rethrow so a canceled coroutine is not
        // turned into a generic store failure.
        throw error
    } catch (error: ChatDraftStoreException) {
        throw error
    } catch (_: Exception) {
        throw ChatDraftStoreException()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_chat_drafts"
        // Versioned namespace so a future key-format change can be migrated
        // by bumping the prefix instead of colliding with old records.
        private const val KEY_PREFIX = "chat.draft.v1."
    }
}
