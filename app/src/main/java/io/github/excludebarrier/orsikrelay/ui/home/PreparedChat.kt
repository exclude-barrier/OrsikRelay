package io.github.excludebarrier.orsikrelay.ui.home

import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator

/**
 * Creates one chat (never retried); with [prompt] its composer starts with
 * that prepared request, which only the user sends. Used by Home's tiles
 * and the Server tab's "Verwaltung". Blocking: call it on `Dispatchers.IO`.
 * The request is stored before this returns, so the chat opened next
 * always finds it.
 */
internal suspend fun createPreparedChat(
    creator: SessionCreator,
    drafts: ChatDraftRepository,
    profileId: String,
    title: String?,
    prompt: String?
): SessionCreateResult {
    val created = creator.createSession(profileId, title)
    if (created is SessionCreateResult.Success && prompt != null) {
        drafts.saveDraftNow(profileId, created.sessionId, prompt)
    }
    return created
}
