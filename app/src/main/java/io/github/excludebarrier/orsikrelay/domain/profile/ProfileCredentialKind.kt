package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * Non-secret credential kind of a stored server profile (Mission 017B).
 *
 * [DEVICE_CREDENTIAL] means the profile's credential was issued by a
 * device pairing (`POST /v1/devices/pair`) and can be revoked through
 * the device self-revoke path. [API_KEY] is the pre-existing static
 * key kind: it must NEVER go through the self-revoke path.
 *
 * Deriving the kind reads only the non-secret device metadata; it never
 * reads, copies or exposes the credential itself.
 */
enum class ProfileCredentialKind {
    API_KEY,
    DEVICE_CREDENTIAL
}
