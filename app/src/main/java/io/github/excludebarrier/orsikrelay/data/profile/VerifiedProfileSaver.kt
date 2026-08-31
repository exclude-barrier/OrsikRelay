package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

fun interface VerifiedProfileSaver {
    fun saveVerified(draft: ProfileDraft): ServerProfile
}
