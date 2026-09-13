package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.data.profile.PairedProfileSaver
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePairingCoordinatorTest {
    @Test
    fun invalidInputMakesNoNetworkOrPersistenceCall() {
        val pairCalls = AtomicInteger()
        val fixture = fixture(pair = {
            pairCalls.incrementAndGet()
            paired()
        })

        val result = fixture.coordinator.connect(" ", BASE_URL, VALID_CODE)

        assertEquals(DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput), result)
        assertEquals(0, pairCalls.get())
        assertEquals(0, fixture.probeCalls.get())
        assertEquals(0, fixture.saveCalls.get())
        assertEquals(0, fixture.revokeCalls.get())
    }

    @Test
    fun pairFailureIsReturnedWithoutProbeSaveOrRevoke() {
        val fixture = fixture(pair = {
            DevicePairResult.Failure(DevicePairError.PairingCodeInvalid)
        })

        val result = fixture.coordinator.connect("Server", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Failure(
                DevicePairingFailure.Pairing(DevicePairError.PairingCodeInvalid)
            ),
            result
        )
        assertEquals(0, fixture.probeCalls.get())
        assertEquals(0, fixture.saveCalls.get())
        assertEquals(0, fixture.revokeCalls.get())
    }

    @Test
    fun authenticatedProbeFailureRevokesOnceAndNeverSaves() {
        val fixture = fixture(probe = {
            HermesConnectionResult.Failure(ConnectionFailureReason.INCOMPATIBLE_API)
        })

        val result = fixture.coordinator.connect("Server", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Failure(
                DevicePairingFailure.Verification(ConnectionFailureReason.INCOMPATIBLE_API)
            ),
            result
        )
        assertEquals(1, fixture.probeCalls.get())
        assertEquals(0, fixture.saveCalls.get())
        assertEquals(1, fixture.revokeCalls.get())
    }

    @Test
    fun missingDeviceCapabilityRevokesAndFailsClosed() {
        val fixture = fixture(probe = {
            HermesConnectionResult.Success("test", true, true, true, false)
        })

        val result = fixture.coordinator.connect("Server", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Failure(DevicePairingFailure.IncompatiblePairing),
            result
        )
        assertEquals(0, fixture.saveCalls.get())
        assertEquals(1, fixture.revokeCalls.get())
    }

    @Test
    fun probeExceptionRevokesExactlyOnceAndFailsClosed() {
        val fixture = fixture(probe = { throw IllegalStateException("synthetic probe failure") })

        val result = fixture.coordinator.connect("Server", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Failure(
                DevicePairingFailure.Verification(ConnectionFailureReason.NETWORK)
            ),
            result
        )
        assertEquals(1, fixture.probeCalls.get())
        assertEquals(0, fixture.saveCalls.get())
        assertEquals(1, fixture.revokeCalls.get())
    }

    @Test
    fun persistenceFailureRevokesExactlyOnce() {
        val fixture = fixture(save = { _, _ -> throw IllegalStateException("synthetic") })

        val result = fixture.coordinator.connect("Server", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Failure(DevicePairingFailure.Persistence),
            result
        )
        assertEquals(1, fixture.saveCalls.get())
        assertEquals(1, fixture.revokeCalls.get())
    }

    @Test
    fun successfulPairingProbesPersistsAndDoesNotRevoke() {
        var savedKeyMatches = false
        val fixture = fixture(save = { draft, credential ->
            savedKeyMatches = draft.apiKey === credential.deviceKey
            profile()
        })

        val result = fixture.coordinator.connect("  Server  ", BASE_URL, VALID_CODE)

        assertEquals(
            DevicePairingOutcome.Success("test", PROFILE_ID, true),
            result
        )
        assertEquals(1, fixture.probeCalls.get())
        assertEquals(1, fixture.saveCalls.get())
        assertEquals(0, fixture.revokeCalls.get())
        assertTrue(savedKeyMatches)
    }

    private fun fixture(
        pair: () -> DevicePairResult = { paired() },
        probe: () -> HermesConnectionResult = {
            HermesConnectionResult.Success("test", true, true, true, true)
        },
        save: (io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft, DeviceCredential) -> ServerProfile =
            { _, _ -> profile() }
    ): Fixture {
        val probeCalls = AtomicInteger()
        val saveCalls = AtomicInteger()
        val revokeCalls = AtomicInteger()
        val coordinator = DevicePairingCoordinator(
            pairTransport = DevicePairTransport { _, _ -> pair() },
            connectionProbe = ConnectionProbe {
                probeCalls.incrementAndGet()
                probe()
            },
            pairedProfileSaver = PairedProfileSaver { draft, credential ->
                saveCalls.incrementAndGet()
                save(draft, credential)
            },
            selfRevokeTransport = DeviceSelfRevokeTransport { _, _ ->
                revokeCalls.incrementAndGet()
                DeviceSelfRevokeResult.Revoked(true, DEVICE_ID)
            }
        )
        return Fixture(coordinator, probeCalls, saveCalls, revokeCalls)
    }

    private data class Fixture(
        val coordinator: DevicePairingCoordinator,
        val probeCalls: AtomicInteger,
        val saveCalls: AtomicInteger,
        val revokeCalls: AtomicInteger
    )

    private companion object {
        const val BASE_URL = "https://example.invalid/p/test"
        const val VALID_CODE = "ABCDEFGHJKMNPQRSTVWXYZ23"
        const val PROFILE_ID = "profile-id"
        const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        val DEVICE_KEY = "hd_" + "A".repeat(43)

        fun paired() = DevicePairResult.Paired(
            DeviceCredential(
                deviceId = DEVICE_ID,
                deviceKey = SecretValue(DEVICE_KEY),
                profile = "default",
                label = "Android",
                permissions = listOf("approvals", "chat", "runs"),
                issuedAt = 1.0
            )
        )

        fun profile() = ServerProfile.create(PROFILE_ID, "Server", BASE_URL)
    }
}
