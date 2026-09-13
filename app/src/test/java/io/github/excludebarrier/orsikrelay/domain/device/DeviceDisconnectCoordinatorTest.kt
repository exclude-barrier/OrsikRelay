package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Mission 017B: the disconnect orchestration contract.
 *
 * - exactly ONE self-revoke per confirmed disconnect, never a retry;
 * - the local profile is removed through the verified [ProfileAdmin]
 *   deletion path only after a `Revoked` answer (both `revoked = true`
 *   and `revoked = false` count as acknowledged);
 * - a load failure, a revoke failure or a revoke exception deletes
 *   NOTHING locally and reports the closed error without raw values;
 * - the sealed [SecretValue] is passed through by identity and never
 *   appears in any result.
 */
class DeviceDisconnectCoordinatorTest {

    @Test
    fun confirmedDisconnectRevokesExactlyOnceAndDeletesLocally() {
        val fixture = fixture(revokeResult = DeviceSelfRevokeResult.Revoked(true, DEVICE_ID))

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(DeviceDisconnectResult.Disconnected, result)
        assertEquals(1, fixture.loadCalls.get())
        assertEquals(1, fixture.revokeCalls.get())
        assertEquals(1, fixture.deleteCalls.get())
        assertSame("the sealed key passes through untouched", KEY, fixture.revokedKey)
        assertEquals(BASE_URL, fixture.revokedBaseUrl)
    }

    @Test
    fun alreadyRevokedAnswerStillRemovesTheLocalProfile() {
        // `revoked = false` is the server's "already revoked" answer; the
        // contract still treats it as an acknowledged revocation.
        val fixture = fixture(revokeResult = DeviceSelfRevokeResult.Revoked(false, DEVICE_ID))

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(DeviceDisconnectResult.Disconnected, result)
        assertEquals(1, fixture.revokeCalls.get())
        assertEquals(1, fixture.deleteCalls.get())
    }

    @Test
    fun missingProfileOrCredentialDeletesNothingAndNeverRevokes() {
        val fixture = fixture(load = { null })

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(
            DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState),
            result
        )
        assertEquals(0, fixture.revokeCalls.get())
        assertEquals(0, fixture.deleteCalls.get())
    }

    @Test
    fun throwingLoadDeletesNothingAndNeverRevokes() {
        val fixture = fixture(load = { throw IllegalStateException("synthetic load failure") })

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(
            DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState),
            result
        )
        assertEquals(0, fixture.revokeCalls.get())
        assertEquals(0, fixture.deleteCalls.get())
        assertFalse(result.toString().contains(RAW_KEY))
    }

    @Test
    fun revokeFailureDeletesNothingAndIsNeverRetried() {
        val fixture = fixture(revokeResult =
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidCredentials))

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(
            DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed),
            result
        )
        assertEquals(1, fixture.revokeCalls.get())
        assertEquals(0, fixture.deleteCalls.get())
    }

    @Test
    fun throwingRevokeDeletesNothingAndIsNeverRetried() {
        val fixture = fixture(revokeThrows = true)

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(
            DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed),
            result
        )
        assertEquals(1, fixture.revokeCalls.get())
        assertEquals(0, fixture.deleteCalls.get())
        assertFalse(result.toString().contains(RAW_KEY))
    }

    @Test
    fun successfulRevocationWithFailingLocalCleanupReportsDeletionFailed() {
        val fixture = fixture(delete = { throw IllegalStateException(RAW_KEY) })

        val result = fixture.coordinator.disconnect(PROFILE_ID)

        assertEquals(
            DeviceDisconnectResult.Failure(DeviceDisconnectError.DeletionFailed),
            result
        )
        assertEquals(1, fixture.revokeCalls.get())
        assertEquals(1, fixture.deleteCalls.get())
        assertFalse(
            "no raw key in the closed failure",
            result.toString().contains(RAW_KEY)
        )
    }

    @Test
    fun noOutcomeCarriesTheRawDeviceKey() {
        val fixtures = listOf(
            fixture(revokeResult = DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Network)),
            fixture(load = { throw IllegalStateException(RAW_KEY) })
        )
        fixtures.forEach { fixture ->
            val result = fixture.coordinator.disconnect(PROFILE_ID)
            assertFalse("no raw key in outcome", result.toString().contains(RAW_KEY))
        }
    }

    private fun fixture(
        load: (String) -> DeviceDisconnectTarget? = {
            DeviceDisconnectTarget(baseUrl = BASE_URL, deviceKey = KEY)
        },
        revokeResult: DeviceSelfRevokeResult = DeviceSelfRevokeResult.Revoked(true, DEVICE_ID),
        revokeThrows: Boolean = false,
        delete: (String) -> Unit = {}
    ): Fixture {
        val loadCalls = AtomicInteger()
        val revokeCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val fixture = Fixture(loadCalls, revokeCalls, deleteCalls)
        val source = DeviceDisconnectSource { profileId ->
            loadCalls.incrementAndGet()
            load(profileId)
        }
        fixture.coordinator = DeviceDisconnectCoordinator(
            disconnectSource = source,
            selfRevokeTransport = DeviceSelfRevokeTransport { baseUrl, key ->
                revokeCalls.incrementAndGet()
                fixture.revokedBaseUrl = baseUrl
                fixture.revokedKey = key
                if (revokeThrows) {
                    throw IllegalStateException("synthetic revoke failure")
                }
                revokeResult
            },
            profileAdmin = object : ProfileAdmin {
                override fun listProfiles(): List<ServerProfile> = emptyList()

                override fun deleteProfile(profileId: String) {
                    deleteCalls.incrementAndGet()
                    delete(profileId)
                }

                override fun selectActiveServer(profileId: String) = Unit

                override fun credentialKind(profileId: String) =
                    ProfileCredentialKind.DEVICE_CREDENTIAL
            }
        )
        return fixture
    }

    private class Fixture(
        val loadCalls: AtomicInteger,
        val revokeCalls: AtomicInteger,
        val deleteCalls: AtomicInteger
    ) {
        lateinit var coordinator: DeviceDisconnectCoordinator
        var revokedBaseUrl: String? = null
        var revokedKey: SecretValue? = null
    }

    private companion object {
        const val PROFILE_ID = "profile-id"
        const val BASE_URL = "https://example.invalid/p/test"
        const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        val RAW_KEY = "hd_" + "A".repeat(43) // synthetic 46-char device key fixture
        val KEY = SecretValue(RAW_KEY)
    }
}
