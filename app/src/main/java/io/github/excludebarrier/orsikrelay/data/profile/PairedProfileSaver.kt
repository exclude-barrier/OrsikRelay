package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

/** Persists a newly paired profile only after its authenticated probe succeeded. */
fun interface PairedProfileSaver {
    fun saveVerifiedPaired(
        draft: ProfileDraft,
        credential: DeviceCredential
    ): ServerProfile
}
