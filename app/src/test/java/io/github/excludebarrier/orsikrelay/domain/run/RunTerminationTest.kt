package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closed classification of a failed turn into the three ownership
 * outcomes. Only an authoritative server terminal or a proven pre-run
 * rejection may release the single run slot.
 */
class RunTerminationTest {

    @Test
    fun serverTerminalErrorIsAuthoritativeRegardlessOfConfirmation() {
        assertEquals(
            RunTermination.AuthoritativeTerminal,
            RunTermination.ofFailure(ChatStreamError.ServerError, runConfirmed = true)
        )
        assertEquals(
            RunTermination.AuthoritativeTerminal,
            RunTermination.ofFailure(ChatStreamError.ServerError, runConfirmed = false)
        )
    }

    @Test
    fun confirmedRunTurnsEveryLocalFailureIntoUnknown() {
        listOf(
            ChatStreamError.Network,
            ChatStreamError.Timeout,
            ChatStreamError.Tls,
            ChatStreamError.IncompatibleApi,
            ChatStreamError.ResumeUnavailable,
            ChatStreamError.ResumeGap,
            ChatStreamError.InvalidCredentials,
            ChatStreamError.Http,
            ChatStreamError.ServerUnavailable,
            ChatStreamError.RateLimited
        ).forEach { error ->
            assertEquals(
                "confirmed + $error must be unknown",
                RunTermination.TransportOutcomeUnknown,
                RunTermination.ofFailure(error, runConfirmed = true)
            )
        }
    }

    @Test
    fun provenPreRunRejectionsAreLocalRequests() {
        listOf(
            ChatStreamError.InvalidInput,
            ChatStreamError.InvalidCredentials,
            ChatStreamError.SessionNotFound,
            ChatStreamError.Conflict,
            ChatStreamError.RateLimited,
            ChatStreamError.Http
        ).forEach { error ->
            assertEquals(
                "unconfirmed + $error must be a local rejection",
                RunTermination.LocalRequestRejected,
                RunTermination.ofFailure(error, runConfirmed = false)
            )
        }
    }

    @Test
    fun unconfirmedTransportFailuresAreUnknownBecauseThePostMayHaveLanded() {
        listOf(
            ChatStreamError.Network,
            ChatStreamError.Timeout,
            ChatStreamError.Tls,
            ChatStreamError.IncompatibleApi,
            ChatStreamError.ResumeUnavailable,
            ChatStreamError.ResumeGap,
            ChatStreamError.ServerUnavailable
        ).forEach { error ->
            assertEquals(
                "unconfirmed + $error must stay unknown",
                RunTermination.TransportOutcomeUnknown,
                RunTermination.ofFailure(error, runConfirmed = false)
            )
        }
    }
}
