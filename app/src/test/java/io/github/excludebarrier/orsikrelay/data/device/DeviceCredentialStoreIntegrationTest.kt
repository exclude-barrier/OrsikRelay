package io.github.excludebarrier.orsikrelay.data.device

import io.github.excludebarrier.orsikrelay.data.profile.DeviceCredentialSaver
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepositoryException
import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mission 017A: credential-store integration of the paired device key
 * at the JVM seam level (synthetic stores, following the existing
 * CredentialStore test patterns). The device key must travel through the
 * SAME CredentialStore infrastructure as the API key (same protection
 * levels on the real Android Keystore store), and it must never appear
 * in the metadata store, in the profile repository, or in any result.
 */
class DeviceCredentialStoreIntegrationTest {

    @Test
    fun deviceKeyRoundTripsThroughTheCredentialStoreLikeAnApiKey() {
        val credentials = FakeCredentialStore()
        val metadata = InMemoryDeviceCredentialMetadataStore()
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))

        // The key is stored under the profile id in the credential
        // store — exactly where the API key lives.
        val stored = credentials.get(PROFILE_ID)
        assertNotNull(stored)
        assertTrue(stored!!.useRaw { it == DEVICE_KEY_RAW })
        // The metadata store holds the non-secret half only.
        val meta = metadata.get(PROFILE_ID)
        assertNotNull(meta)
        assertEquals(DEVICE_ID, meta!!.deviceId)
        assertEquals(DEVICE_PROFILE, meta.profile)
        assertEquals(listOf("approvals", "chat", "runs"), meta.permissions)
        // The raw key must not be derivable from the metadata record.
        assertFalse(meta.toString().contains(DEVICE_KEY_RAW))
    }

    @Test
    fun savingReplacesThePreviousDeviceCredential() {
        val credentials = FakeCredentialStore()
        val metadata = InMemoryDeviceCredentialMetadataStore()
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))
        saver.saveDeviceCredential(profile(), credential(REPLACEMENT_KEY_RAW))

        val stored = credentials.get(PROFILE_ID)!!
        assertTrue(stored.useRaw { it == REPLACEMENT_KEY_RAW })
        assertFalse(stored.useRaw { it == DEVICE_KEY_RAW })
    }

    @Test
    fun deviceKeyBecomesTheProfileCredentialReplacingTheApiKey() {
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(OLD_API_KEY))
        )
        val metadata = InMemoryDeviceCredentialMetadataStore()
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))

        // After pairing, the credential store holds the DEVICE key for
        // the profile: subsequent requests authenticate with it.
        val stored = credentials.get(PROFILE_ID)!!
        assertTrue(stored.useRaw { it == DEVICE_KEY_RAW })
        assertFalse(stored.useRaw { it == OLD_API_KEY })
    }

    @Test
    fun metadataStoreFailureRollsBackTheCredentialAndStaysClosed() {
        val credentials = FakeCredentialStore()
        val metadata = InMemoryDeviceCredentialMetadataStore().apply { failPuts = true }
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        val thrown = try {
            saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))
            null
        } catch (error: DeviceCredentialStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected DeviceCredentialStoreException")
            null
        }
        assertNotNull(thrown)
        assertEquals(DeviceCredentialStoreException.MESSAGE, thrown!!.message)
        assertFalse(thrown.toString().contains(DEVICE_KEY_RAW))
        // Rollback: the half-persisted key is removed again.
        assertNull(credentials.get(PROFILE_ID))
    }

    @Test
    fun metadataFailureRestoresThePreviousProfileCredential() {
        val previous = SecretValue(OLD_API_KEY)
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to previous))
        val metadata = InMemoryDeviceCredentialMetadataStore().apply { failPuts = true }
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        try {
            saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))
            fail("Expected DeviceCredentialStoreException")
        } catch (_: DeviceCredentialStoreException) {
            // expected closed failure
        }

        val restored = credentials.get(PROFILE_ID)
        assertNotNull(restored)
        assertTrue(restored === previous)
        assertTrue(restored!!.useRaw { it == OLD_API_KEY })
        assertFalse(restored.useRaw { it == DEVICE_KEY_RAW })
    }

    @Test
    fun failingRollbackStillReportsTheClosedBoundaryOnly() {
        // Both the metadata write AND the rollback delete fail: the
        // saver must still report the closed, input-free exception (no
        // key, no underlying detail) — the half-persisted key state is
        // documented as a best-effort rollback limitation.
        val credentials = FakeCredentialStore(failDeletes = true)
        val metadata = InMemoryDeviceCredentialMetadataStore().apply { failPuts = true }
        val saver = CredentialStoreDeviceCredentialSaver(credentials, metadata)

        val thrown = try {
            saver.saveDeviceCredential(profile(), credential(DEVICE_KEY_RAW))
            fail("Expected DeviceCredentialStoreException")
            null
        } catch (error: DeviceCredentialStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected DeviceCredentialStoreException")
            null
        }
        assertNotNull(thrown)
        assertEquals(DeviceCredentialStoreException.MESSAGE, thrown!!.message)
        assertFalse(thrown.toString().contains(DEVICE_KEY_RAW))
    }

    @Test
    fun repositorySavesDeviceCredentialOnlyForAnExistingProfile() {
        val credentials = FakeCredentialStore()
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val deviceMetadata = InMemoryDeviceCredentialMetadataStore()
        val repository = ServerProfileRepository(
            metadataStore = metadata,
            credentialStore = credentials,
            idFactory = { PROFILE_ID },
            deviceCredentialSaver = CredentialStoreDeviceCredentialSaver(
                credentials, deviceMetadata
            )
        )

        repository.saveDeviceCredential(PROFILE_ID, credential(DEVICE_KEY_RAW))

        assertTrue(credentials.get(PROFILE_ID)!!.useRaw { it == DEVICE_KEY_RAW })
        assertNotNull(deviceMetadata.get(PROFILE_ID))
        // The closed boundary holds: no key or profile detail escapes.
        assertFalse(credential(DEVICE_KEY_RAW).toString().contains(DEVICE_KEY_RAW))
    }

    @Test
    fun repositoryRejectsMissingProfileAndMissingSaverWithTheClosedBoundary() {
        val credentials = FakeCredentialStore()
        val emptyMetadata = FakeMetadataStore()

        // Missing profile: closed failure, no credential write.
        val missingProfileRepository = ServerProfileRepository(
            metadataStore = emptyMetadata,
            credentialStore = credentials,
            idFactory = { PROFILE_ID },
            deviceCredentialSaver = CredentialStoreDeviceCredentialSaver(
                credentials, InMemoryDeviceCredentialMetadataStore()
            )
        )
        assertClosedBoundary {
            missingProfileRepository.saveDeviceCredential(
                PROFILE_ID, credential(DEVICE_KEY_RAW)
            )
        }
        assertNull(credentials.get(PROFILE_ID))

        // No saver installed: the existing API-key-only path is
        // untouched and the device operation fails closed.
        val noSaverRepository = ServerProfileRepository(
            metadataStore = FakeMetadataStore(mutableListOf(profile())),
            credentialStore = credentials,
            idFactory = { PROFILE_ID }
        )
        assertClosedBoundary {
            noSaverRepository.saveDeviceCredential(
                PROFILE_ID, credential(DEVICE_KEY_RAW)
            )
        }
        assertNull(credentials.get(PROFILE_ID))
    }

    @Test
    fun failingSaverIsReportedWithTheClosedBoundaryOnly() {
        val credentials = FakeCredentialStore()
        val failingSaver = DeviceCredentialSaver { _, _ ->
            throw IllegalStateException("synthetic-sensitive-detail")
        }
        val repository = ServerProfileRepository(
            metadataStore = FakeMetadataStore(mutableListOf(profile())),
            credentialStore = credentials,
            idFactory = { PROFILE_ID },
            deviceCredentialSaver = failingSaver
        )

        val thrown = try {
            repository.saveDeviceCredential(PROFILE_ID, credential(DEVICE_KEY_RAW))
            fail("Expected ServerProfileRepositoryException")
            null
        } catch (error: ServerProfileRepositoryException) {
            error
        } catch (_: Throwable) {
            fail("Expected ServerProfileRepositoryException")
            null
        }
        assertNotNull(thrown)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
        assertFalse(thrown.toString().contains(DEVICE_KEY_RAW))
    }

    private fun assertClosedBoundary(action: () -> Unit) {
        try {
            action()
            fail("Expected ServerProfileRepositoryException")
        } catch (error: ServerProfileRepositoryException) {
            assertFalse(error.toString().contains(DEVICE_KEY_RAW))
        }
    }

    private fun credential(rawKey: String) = DeviceCredential(
        deviceId = DEVICE_ID,
        deviceKey = SecretValue(rawKey),
        profile = DEVICE_PROFILE,
        label = "fixture-label",
        permissions = listOf("approvals", "chat", "runs"),
        issuedAt = 1757000000.125
    )

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private class FakeCredentialStore(
        val values: MutableMap<String, SecretValue> = mutableMapOf(),
        val failDeletes: Boolean = false
    ) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) {
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? = values[profileId]

        override fun delete(profileId: String) {
            if (failDeletes) throw IllegalStateException("synthetic-delete-failure")
            values.remove(profileId)
        }
    }

    private class FakeMetadataStore(
        val profiles: MutableList<ServerProfile> = mutableListOf()
    ) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun get(profileId: String): ServerProfile? =
            profiles.firstOrNull { it.id == profileId }

        override fun list(): List<ServerProfile> = profiles.sortedBy { it.id }

        override fun delete(profileId: String) {
            profiles.removeAll { it.id == profileId }
        }
    }

    private class InMemoryDeviceCredentialMetadataStore(
        private val values: MutableMap<String, DeviceCredentialMetadata> = mutableMapOf()
    ) : DeviceCredentialMetadataStore {
        var failPuts = false

        override fun put(profileId: String, metadata: DeviceCredentialMetadata) {
            if (failPuts) throw DeviceCredentialStoreException()
            values[profileId] = metadata
        }

        override fun get(profileId: String): DeviceCredentialMetadata? = values[profileId]

        override fun delete(profileId: String) {
            values.remove(profileId)
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val BASE_URL = "https://example.invalid/p/android"
        const val OLD_API_KEY = "fixture-api-key"
        const val DEVICE_ID = "3f9c2a1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        const val DEVICE_KEY_RAW = "hd_0123456789012345678901234567890123456789012"
        const val REPLACEMENT_KEY_RAW = "hd_9998887776665554443332221110009998887776665"
        const val DEVICE_PROFILE = "fixture-profile"
    }
}
