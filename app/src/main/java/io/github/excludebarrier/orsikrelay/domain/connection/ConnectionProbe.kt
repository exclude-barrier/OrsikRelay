package io.github.excludebarrier.orsikrelay.domain.connection

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft

fun interface ConnectionProbe {
    fun probe(draft: ProfileDraft): HermesConnectionResult
}
