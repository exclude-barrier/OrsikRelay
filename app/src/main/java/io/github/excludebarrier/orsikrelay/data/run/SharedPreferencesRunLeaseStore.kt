package io.github.excludebarrier.orsikrelay.data.run

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.excludebarrier.orsikrelay.domain.run.RunLease
import io.github.excludebarrier.orsikrelay.domain.run.RunLeaseStore

/**
 * App-private [RunLeaseStore] on top of [SharedPreferences] (OR-09), following
 * the repository's established persistence pattern (see
 * [io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftStore]).
 *
 * Exactly one lease exists at a time (single-slot client). The three IDs are
 * written in one `commit()`; a record with any missing or blank field is
 * treated as absent. Only identifiers are stored - never message text, tool
 * data or credentials. All calls do blocking I/O and run off the main thread
 * (the controller uses its IO dispatcher). The preferences file is excluded
 * from backup by the manifest's `allowBackup=false`.
 */
@SuppressLint("UseKtx")
class SharedPreferencesRunLeaseStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : RunLeaseStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): RunLease? {
        val profileId = preferences.getString(KEY_PROFILE, null)
        val sessionId = preferences.getString(KEY_SESSION, null)
        val runId = preferences.getString(KEY_RUN, null)
        if (profileId.isNullOrBlank() || sessionId.isNullOrBlank() || runId.isNullOrBlank()) {
            return null
        }
        return RunLease(profileId = profileId, sessionId = sessionId, runId = runId)
    }

    override fun save(lease: RunLease) {
        val written = preferences.edit()
            .putString(KEY_PROFILE, lease.profileId)
            .putString(KEY_SESSION, lease.sessionId)
            .putString(KEY_RUN, lease.runId)
            .commit()
        // Durable write on purpose (process death is the point); the closed
        // failure carries no lease data.
        check(written) { "run lease write failed" }
    }

    override fun clear() {
        val written = preferences.edit()
            .remove(KEY_PROFILE)
            .remove(KEY_SESSION)
            .remove(KEY_RUN)
            .commit()
        check(written) { "run lease clear failed" }
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_run_lease"
        // Versioned keys so a later format change can migrate by bumping them.
        private const val KEY_PROFILE = "run.lease.v1.profile"
        private const val KEY_SESSION = "run.lease.v1.session"
        private const val KEY_RUN = "run.lease.v1.run"
    }
}
