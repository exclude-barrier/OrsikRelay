package io.github.excludebarrier.orsikrelay.security.credential

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

interface CredentialStore {
    fun put(profileId: String, secret: SecretValue)
    fun get(profileId: String): SecretValue?
    fun delete(profileId: String)
}
