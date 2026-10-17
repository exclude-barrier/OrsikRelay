package io.github.excludebarrier.orsikrelay.data.chat

/**
 * Closed, fixed failure surface for chat-draft persistence.
 *
 * Mirrors [io.github.excludebarrier.orsikrelay.data.profile.ProfileMetadataStoreException]:
 * every store failure maps to this one generic message. It never carries
 * draft text, key material, IDs or any dynamic detail, so no user input can
 * leak through an error.
 */
class ChatDraftStoreException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Chat draft persistence operation failed"
    }
}
