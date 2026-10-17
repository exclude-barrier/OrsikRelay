package io.github.excludebarrier.orsikrelay.data.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * App-private [ActiveServerSelectionStore] on top of [SharedPreferences].
 *
 * Stores exactly ONE plain string — the selected profile ID — in a
 * dedicated versioned key. Nothing else is stored: no API key, no URL, no
 * draft or chat data. A missing key reads null; a blank stored ID is
 * treated as absent (the resolver validates the ID against the metadata
 * and credential stores, so a corrupt value can never break startup).
 *
 * Every operation uses `commit()` (durable, synchronous) and must run off
 * the main thread; every failure maps to the closed
 * [ActiveServerSelectionStoreException] and never echoes the stored ID.
 */
@SuppressLint("UseKtx")
class SharedPreferencesActiveServerSelectionStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : ActiveServerSelectionStore {

    private val preferences: SharedPreferences

    init {
        if (preferencesName.isBlank()) throw ActiveServerSelectionStoreException()
        preferences = try {
            context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
        } catch (_: Exception) {
            throw ActiveServerSelectionStoreException()
        }
    }

    override fun read(): String? = selectionCall {
        preferences.getString(SELECTION_KEY, null)
    }

    override fun write(profileId: String) {
        if (profileId.isBlank()) throw ActiveServerSelectionStoreException()
        selectionCall {
            if (!preferences.edit().putString(SELECTION_KEY, profileId).commit()) {
                throw ActiveServerSelectionStoreException()
            }
        }
    }

    override fun clear() {
        selectionCall {
            // Idempotent: removing an absent key still commits successfully.
            if (!preferences.edit().remove(SELECTION_KEY).commit()) {
                throw ActiveServerSelectionStoreException()
            }
        }
    }

    private inline fun <T> selectionCall(block: () -> T): T = try {
        block()
    } catch (error: ActiveServerSelectionStoreException) {
        throw error
    } catch (_: Exception) {
        throw ActiveServerSelectionStoreException()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_server_selection"
        // Versioned key so a future format change can be migrated without
        // colliding with the current record (same scheme as the chat-draft
        // store's key prefix).
        internal const val SELECTION_KEY = "active.server.v1"
    }
}
