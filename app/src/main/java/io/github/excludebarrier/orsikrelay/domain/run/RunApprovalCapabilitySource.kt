package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Fail-closed reader of the per-profile run-approval capability
 * (Mission 014).
 *
 * The UI depends only on this seam. The production implementation reads the
 * persisted, onboarding-confirmed `features.run_approval_response` value for
 * the given profile and ALWAYS answers `false` on a blank ID, a missing
 * record or any storage failure — the capability can never be inferred as
 * enabled from an absent or corrupt value. It is suspend so the production
 * implementation can perform the store read off the main thread.
 */
fun interface RunApprovalCapabilitySource {
    suspend fun isEnabled(profileId: String): Boolean
}
