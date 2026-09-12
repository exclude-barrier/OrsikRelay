package io.github.excludebarrier.orsikrelay.data.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * App-private [RunApprovalCapabilityStore] on top of [SharedPreferences]
 * (Mission 014).
 *
 * Stores exactly one plain boolean per profile: whether the complete
 * `session_chat_interactive_approvals` protocol-v1 contract was validated.
 * The v2 key namespace intentionally does not trust legacy v1 booleans that
 * represented only `features.run_approval_response`. Nothing else is stored:
 * no API key, URL, draft, chat data or approval content. Missing/corrupt data
 * reads `false` (fail-closed).
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
        internal const val KEY_PREFIX = "run_approval.v2:"
    }
}
