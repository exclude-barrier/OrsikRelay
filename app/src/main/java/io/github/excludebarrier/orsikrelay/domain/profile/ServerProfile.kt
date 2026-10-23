package io.github.excludebarrier.orsikrelay.domain.profile

/** Persisted, non-secret server profile metadata. */
@ConsistentCopyVisibility
data class ServerProfile private constructor(
    val id: String,
    val profileName: String,
    val baseUrl: String
) {
    companion object {
        private const val INVALID_MESSAGE = "Invalid server profile metadata"

        fun create(id: String, profileName: String, baseUrl: String): ServerProfile {
            val normalizedUrl = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            if (
                id.isBlank() ||
                profileName.isBlank() ||
                profileName != profileName.trim() ||
                profileName.length > ProfileFormValidator.PROFILE_NAME_MAX_LENGTH ||
                profileName.any { it.isISOControl() } ||
                normalizedUrl == null ||
                normalizedUrl != baseUrl
            ) {
                throw IllegalArgumentException(INVALID_MESSAGE)
            }
            return ServerProfile(
                id = id,
                profileName = profileName,
                baseUrl = baseUrl
            )
        }
    }
}
