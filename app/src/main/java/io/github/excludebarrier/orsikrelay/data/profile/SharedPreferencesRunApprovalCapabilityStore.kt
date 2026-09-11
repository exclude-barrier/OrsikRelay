package io.github.excludebarrier.orsikrelay.data.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * App-private [RunApprovalCapabilityStore] on top of [SharedPreferences]
 * (Mission 014).
 *
 * Stores exactly ONE plain boolean per profile — the server-confirmed
 * `features.run_approval_response` capability — in a dedicated versioned
 * key per profile ID. Nothing else is stored: no API key, no URL, no draft
 * or chat data, and no approval content. A missing key reads `false`
 * (fail-closed) and a blank profile ID is rejected, so a corrupt value can
 * never enable the approval UI.
 *
 * Every operation uses `commit()` (durable, synchronous) and must run off
 * the main thread; every failure maps to the closed
 * [RunApprovalCapabilityStoreException] and never echoes the profile ID.
 */
@SuppressLint("UseKtx")
class SharedPreferencesRunApprovalCapabilityStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : RunApprovalCapabilityStore {

    private val preferences: SharedPreferences

    init {
        if (preferencesName.isBlank()) throw RunApprovalCapabilityStoreException()
        preferences = try {
            context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
        } catch (_: Exception) {
            throw RunApprovalCapabilityStoreException()
        }
    }

    override fun read(profileId: String): Boolean = call {
        // Fail-closed: absent, non-boolean or corrupt reads are false.
        preferences.getBoolean(keyFor(profileId), false)
    }

    override fun write(profileId: String, enabled: Boolean) {
        if (profileId.isBlank()) throw RunApprovalCapabilityStoreException()
        call {
            if (!preferences.edit().putBoolean(keyFor(profileId), enabled).commit()) {
                throw RunApprovalCapabilityStoreException()
            }
        }
    }

    override fun clear(profileId: String) {
        if (profileId.isBlank()) throw RunApprovalCapabilityStoreException()
        call {
            // Idempotent: removing an absent key still commits successfully.
            if (!preferences.edit().remove(keyFor(profileId)).commit()) {
                throw RunApprovalCapabilityStoreException()
            }
        }
    }

    private fun keyFor(profileId: String): String = KEY_PREFIX + profileId

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (error: RunApprovalCapabilityStoreException) {
        throw error
    } catch (_: Exception) {
        throw RunApprovalCapabilityStoreException()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_run_approval_capability"
        // Versioned key prefix so a future format change can be migrated
        // without colliding with the current records (same scheme as the
        // active-server selection store's key).
        internal const val KEY_PREFIX = "run_approval.v1:"
    }
}
