package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface ProfileConnectionOutcome {
    /**
     * A successful probe + persistence. [sessionChatInteractiveApprovals]
     * records strict validation of the complete protocol-v1 contract so the
     * onboarding flow can persist it for this verified profile. It is
     * metadata, never a credential.
     */
    data class Success(
        val version: String,
        val profileId: String,
        val sessionChatInteractiveApprovals: Boolean
    ) : ProfileConnectionOutcome

    data class Failure(
        val reason: ConnectionFailureReason
    ) : ProfileConnectionOutcome

    data object PersistenceFailure : ProfileConnectionOutcome
}
