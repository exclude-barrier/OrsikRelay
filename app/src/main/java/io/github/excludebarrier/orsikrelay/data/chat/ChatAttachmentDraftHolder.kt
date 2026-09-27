package io.github.excludebarrier.orsikrelay.data.chat

import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft

/**
 * Process-scoped holder for the attachment the user has picked but not sent
 * yet (P5).
 *
 * Why this exists and why it is in memory only: the picker's `content://`
 * read permission lasts for the life of the process, so an attachment draft
 * can only be re-opened inside the same live process. Holding it here is
 * exactly that lifetime - it survives an Activity/configuration recreation
 * (the chat view model is re-created by a configuration change) and dies with
 * the process. Nothing is written to disk, so no stale URI, name or MIME type
 * can ever be restored after process death.
 *
 * Keyed by (profileId, sessionId), so an attachment can never leak into
 * another chat: switching session or server simply does not find it, and
 * [clear] removes it on a successful send, an explicit remove, or teardown.
 */
object ChatAttachmentDraftHolder {

    private val drafts = HashMap<String, ChatAttachmentDraft>()

    /** The draft of exactly this chat, or `null`. */
    @Synchronized
    fun get(profileId: String, sessionId: String): ChatAttachmentDraft? =
        drafts[key(profileId, sessionId)]

    /** Stores (or replaces) this chat's draft. */
    @Synchronized
    fun put(profileId: String, sessionId: String, draft: ChatAttachmentDraft) {
        drafts[key(profileId, sessionId)] = draft
    }

    /** Drops this chat's draft. */
    @Synchronized
    fun clear(profileId: String, sessionId: String) {
        drafts.remove(key(profileId, sessionId))
    }

    private fun key(profileId: String, sessionId: String): String =
        profileId.length.toString() + ":" + profileId + "|" + sessionId
}
