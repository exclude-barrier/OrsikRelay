package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/** Single closed validator for the scoped device-key credential class. */
object DeviceCredentialKey {
    private val pattern = Regex("^hd_[A-Za-z0-9_-]{43}$")

    fun isValid(raw: String): Boolean = pattern.matches(raw)

    fun isValid(secret: SecretValue): Boolean = secret.useRaw(::isValid)
}
