package io.github.excludebarrier.orsikrelay.domain.connection

sealed interface ProfileConnectionOutcome {
    data class Success(
        val version: String,
        val profileId: String
    ) : ProfileConnectionOutcome

    data class Failure(
        val reason: ConnectionFailureReason
    ) : ProfileConnectionOutcome

    data object PersistenceFailure : ProfileConnectionOutcome
}
