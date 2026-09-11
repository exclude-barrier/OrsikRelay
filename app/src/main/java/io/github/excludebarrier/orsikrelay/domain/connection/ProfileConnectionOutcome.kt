package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface ProfileConnectionOutcome {
    /**
     * A successful probe + persistence. [runApprovalResponse] (Mission
     * 014) carries the server-confirmed `features.run_approval_response`
     * capability flag from the probe so the onboarding flow can persist it
     * for the freshly verified profile; it is metadata, never a credential.
     */
    data class Success(
        val version: String,
        val profileId: String,
        val runApprovalResponse: Boolean
    ) : ProfileConnectionOutcome

    data class Failure(
        val reason: ConnectionFailureReason
    ) : ProfileConnectionOutcome

    data object PersistenceFailure : ProfileConnectionOutcome
}
