package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * Validated onboarding values (Mission 002A).
 *
 * Lives exclusively in memory; it is neither persisted nor sent in this
 * ticket.
 *
 * Intentionally not a data class: the default `toString()` would embed
 * the raw API key. The explicit `toString()` below prints only
 * `[REDACTED]` for the key.
 */
class ProfileDraft(
    val profileName: String,
    val baseUrl: String,
    val apiKey: SecretValue
) {

    override fun toString(): String =
        "ProfileDraft(profileName=$profileName, baseUrl=$baseUrl, apiKey=[REDACTED])"
}
