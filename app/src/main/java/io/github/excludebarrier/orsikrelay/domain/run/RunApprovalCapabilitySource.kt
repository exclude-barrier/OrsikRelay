package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Fail-closed reader of the per-profile run-approval capability.
 *
 * The production implementation reads whether the complete versioned mobile
 * approval contract was confirmed for the profile. It always answers `false`
 * on a blank ID, missing record or storage failure; support is never inferred
 * from absent, corrupt or legacy metadata. It is suspend so storage reads can
 * run off the main thread.
 */
fun interface RunApprovalCapabilitySource {
    suspend fun isEnabled(profileId: String): Boolean
}
