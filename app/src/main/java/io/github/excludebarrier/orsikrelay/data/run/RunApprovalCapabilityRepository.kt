package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.RunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalCapabilitySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunApprovalCapabilitySource] (Mission 014).
 *
 * Reads the persisted, onboarding-confirmed capability for the given profile
 * from [RunApprovalCapabilityStore]. Every failure path is fail-closed:
 * a blank profile ID, an absent record or a store exception all answer
 * `false`, so the approval UI can only ever be enabled by an explicitly
 * stored, onboarding-confirmed `true`. No approval content is read, written
 * or modelled here.
 */
class RunApprovalCapabilityRepository(
    private val capabilityStore: RunApprovalCapabilityStore
) : RunApprovalCapabilitySource {

    override suspend fun isEnabled(profileId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (profileId.isBlank()) return@withContext false
            try {
                capabilityStore.read(profileId)
            } catch (_: Exception) {
                // Fail-closed: a storage failure must never enable the UI.
                false
            }
        }
}
