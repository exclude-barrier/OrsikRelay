package io.github.excludebarrier.orsikrelay.domain.connection

import io.github.excludebarrier.orsikrelay.data.profile.VerifiedProfileSaver
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConnectionCoordinatorTest {

    @Test
    fun `success probes before saving and saves exactly once`() {
        val events = mutableListOf<String>()
        var saveCount = 0
        val probe = ConnectionProbe {
            events += "probe"
            HermesConnectionResult.Success("0.20-test", true, true)
        }
        val saver = VerifiedProfileSaver {
            events += "save"
            saveCount += 1
            profile()
        }

        val outcome = ProfileConnectionCoordinator(probe, saver).connect(draft())

        assertEquals(listOf("probe", "save"), events)
        assertEquals(1, saveCount)
        assertEquals(
            ProfileConnectionOutcome.Success("0.20-test", PROFILE_ID),
            outcome
        )
    }

    @Test
    fun `every probe failure skips persistence`() {
        ConnectionFailureReason.entries.forEach { reason ->
            var saveCount = 0
            val coordinator = ProfileConnectionCoordinator(
                connectionProbe = ConnectionProbe {
                    HermesConnectionResult.Failure(reason)
                },
                profileSaver = VerifiedProfileSaver {
                    saveCount += 1
                    profile()
                }
            )

            val outcome = coordinator.connect(draft())

            assertEquals(ProfileConnectionOutcome.Failure(reason), outcome)
            assertEquals(0, saveCount)
        }
    }

    @Test
    fun `persistence exception becomes data-free persistence failure`() {
        val coordinator = ProfileConnectionCoordinator(
            connectionProbe = ConnectionProbe {
                HermesConnectionResult.Success("0.20-test", true, true)
            },
            profileSaver = VerifiedProfileSaver {
                throw IllegalStateException("synthetic persistence failure")
            }
        )

        val outcome = coordinator.connect(draft())

        assertSame(ProfileConnectionOutcome.PersistenceFailure, outcome)
        assertFalse(outcome.toString().contains(FIXTURE_SECRET))
    }

    @Test
    fun `all outcomes remain free of the raw credential`() {
        val outcomes = buildList {
            add(ProfileConnectionOutcome.Success("0.20-test", PROFILE_ID))
            ConnectionFailureReason.entries.forEach { reason ->
                add(ProfileConnectionOutcome.Failure(reason))
            }
            add(ProfileConnectionOutcome.PersistenceFailure)
        }

        assertTrue(outcomes.none { it.toString().contains(FIXTURE_SECRET) })
    }

    private fun draft() = ProfileDraft(
        profileName = "Fixture",
        baseUrl = BASE_URL,
        apiKey = SecretValue(FIXTURE_SECRET)
    )

    private fun profile() = ServerProfile.create(
        id = PROFILE_ID,
        profileName = "Fixture",
        baseUrl = BASE_URL
    )

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val BASE_URL = "https://example.invalid/p/test"
        const val FIXTURE_SECRET = "fixture-credential"
    }
}
