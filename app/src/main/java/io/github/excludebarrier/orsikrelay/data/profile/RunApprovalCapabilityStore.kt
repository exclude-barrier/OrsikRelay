package io.github.excludebarrier.orsikrelay.data.profile

/**
 * Synchronous, app-private storage seam for the per-profile run-approval
 * capability.
 *
 * The capability is private local metadata derived from strict validation of
 * the complete `session_chat_interactive_approvals` protocol-v1 contract.
 * For one profile it stores exactly one non-secret boolean. It never stores
 * URL, a draft or any chat/session data, and it never stores approval
 * content (no command, request, run ID or payload).
 *
 * - [write] persists [enabled] for [profileId]; a blank ID is rejected.
 * - [clear] removes the record for [profileId]; it is idempotent.
 * - [read] returns the stored value for [profileId]; an absent or invalid
 *   record reads `false` (fail-closed), so a corrupt value can never enable
 *   the approval UI.
 *
 * Implementations must commit synchronously (the caller runs off the main
 * thread) and must fail with the closed, input-free
 * [RunApprovalCapabilityStoreException].
 */
interface RunApprovalCapabilityStore {
    fun read(profileId: String): Boolean
    fun write(profileId: String, enabled: Boolean)
    fun clear(profileId: String)
}
