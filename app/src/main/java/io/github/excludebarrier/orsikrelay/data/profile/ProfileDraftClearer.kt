package io.github.excludebarrier.orsikrelay.data.profile

/**
 * Narrow seam for removing the ordinary chat-composer drafts of one profile
 * after that profile has been deleted.
 *
 * Placed in the data layer next to [VerifiedProfileSaver]:
 * [ServerProfileRepository] depends on this seam without touching the UI
 * layer or the chat-draft types. Implementations must be idempotent and must
 * never surface profile IDs, draft text, URLs, credentials or underlying
 * exceptions.
 */
fun interface ProfileDraftClearer {
    fun clearProfile(profileId: String)
}
