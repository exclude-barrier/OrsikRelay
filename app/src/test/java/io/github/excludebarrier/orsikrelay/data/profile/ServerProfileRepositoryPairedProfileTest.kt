package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.data.device.DeviceCredentialMetadata
import io.github.excludebarrier.orsikrelay.data.device.DeviceCredentialMetadataStore
import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mission 017B: the atomic paired-profile persistence contract.
 *
 * [ServerProfileRepository.saveVerifiedPaired] writes the paired device
 * credential and the profile metadata in ONE logical transaction:
 *
 * - success: credential saver + metadata save, both committed;
 * - ANY failure: best-effort rollback of credential, device metadata AND
 *   profile metadata, then the fixed, input-free
 *   [ServerProfileRepositoryException] (no raw key, no underlying
 *   exception text);
 * - a repository without a wired device saver fails closed with the
 *   same boundary.
 *
 * The ordinary API-key path [ServerProfileRepository.saveVerified] is
 * not touched by this contract and stays available when no saver is
 * installed.
 */
class ServerProfileRepositoryPairedProfileTest {

    @Test
    fun saveVerifiedPairedCommitsCredentialAndMetadata() {
        val fixture = Fixture()

        val profile = fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)

        assertEquals(FIXTURE_ID, profile.id)
        // The saver's internal credential/metadata writes are recorded too;
        // the transaction contract is: device save BEFORE metadata save and
        // NO rollback.
        assertTrue("device save precedes metadata save",
            fixture.events.indexOf("device.save") < fixture.events.indexOf("metadata.save"))
        assertTrue("no rollback on success", fixture.events.none { it.endsWith(".delete") })
        assertEquals(RAW_KEY, fixture.credentials[profile.id]?.useRawToString())
        assertEquals(METADATA, fixture.deviceMetadata[profile.id])
        assertEquals(profile, fixture.metadata.profiles[profile.id])
    }

    @Test
    fun saveVerifiedPairedRollsBackEverythingWhenTheSaverFails() {
        val fixture = Fixture(failSaver = true)
        // Simulate a saver that wrote the key but reported a failure:
        fixture.credentials[FIXTURE_ID] = CREDENTIAL.deviceKey

        assertRepositoryFailure { fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL) }

        assertTrue("credential rolled back", fixture.credentials.isEmpty())
        assertTrue("device metadata rolled back", fixture.deviceMetadata.isEmpty())
        assertTrue("profile metadata rolled back", fixture.metadata.profiles.isEmpty())
        assertTrue("credential rollback ran", "credential.delete" in fixture.events)
        assertTrue("device metadata rollback ran", "device.delete" in fixture.events)
        assertTrue("profile metadata rollback ran", "metadata.delete" in fixture.events)
    }

    @Test
    fun saveVerifiedPairedRollsBackWhenTheMetadataSaveFails() {
        val fixture = Fixture(failMetadataSave = true)
        fixture.credentials[FIXTURE_ID] = CREDENTIAL.deviceKey

        assertRepositoryFailure { fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL) }

        assertTrue("credential rolled back", fixture.credentials.isEmpty())
        assertTrue("device metadata rolled back", fixture.deviceMetadata.isEmpty())
        assertTrue("profile metadata rolled back", fixture.metadata.profiles.isEmpty())
    }

    @Test
    fun profileMetadataCommitThenThrowRollsBackAllThreeStores() {
        val fixture = Fixture(failMetadataAfterCommit = true)

        val error = assertRepositoryFailure {
            fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)
        }

        assertTrue("credential rolled back", fixture.credentials.isEmpty())
        assertTrue("device metadata rolled back", fixture.deviceMetadata.isEmpty())
        assertTrue("committed profile metadata rolled back", fixture.metadata.profiles.isEmpty())
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(RAW_KEY))
    }

    @Test
    fun rollbackFailuresKeepTheGenericBoundaryAndNeverLeakTheKey() {
        val fixture = Fixture(failSaver = true)
        fixture.credentials[FIXTURE_ID] = CREDENTIAL.deviceKey
        fixture.credentialStore.failDeleteNow = true
        fixture.deviceMetadataStore.failDeleteNow = true
        fixture.metadata.failDelete = true

        val error = assertRepositoryFailure {
            fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)
        }

        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse("no raw key in the exception", error.toString().contains(RAW_KEY))
        assertFalse("no underlying text in the exception", error.toString().contains("synthetic"))
    }

    @Test
    fun saveVerifiedPairedFailsClosedWithoutADeviceSaver() {
        val fixture = Fixture(wireSaver = false)

        assertRepositoryFailure { fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL) }

        assertTrue(fixture.credentials.isEmpty())
        assertTrue(fixture.metadata.profiles.isEmpty())
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun ordinaryApiKeyPathStillWorksWithoutAnyDeviceSeam() {
        val fixture = Fixture(wireSaver = false)

        val profile = fixture.repository.saveVerified(DRAFT)

        assertEquals(FIXTURE_ID, profile.id)
        assertEquals(API_KEY, fixture.credentials[profile.id]?.useRawToString())
        assertEquals(profile, fixture.metadata.profiles[profile.id])
        assertTrue("no device seam touched", fixture.events.none { it.startsWith("device.") })
    }

    @Test
    fun deleteRemovesOrphanedDeviceMetadataBestEffort() {
        val fixture = Fixture()
        val profile = fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)

        fixture.repository.delete(profile.id)

        assertTrue(fixture.metadata.profiles.isEmpty())
        assertTrue(fixture.credentials.isEmpty())
        assertTrue("orphaned device metadata removed", fixture.deviceMetadata.isEmpty())
        assertTrue("device cleanup attempted", "device.delete" in fixture.events)
    }

    @Test
    fun deleteSucceedsEvenWhenTheOrphanedDeviceMetadataCleanupFails() {
        val fixture = Fixture()
        val profile = fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)
        fixture.deviceMetadataStore.failDeleteNow = true

        // Best-effort: the profile deletion must stay successful.
        fixture.repository.delete(profile.id)

        assertTrue(fixture.metadata.profiles.isEmpty())
        assertTrue(fixture.credentials.isEmpty())
    }

    @Test
    fun apiKeyProfileCannotBeLoadedAsDeviceDisconnectTarget() {
        val fixture = Fixture()
        val profile = fixture.repository.saveVerified(DRAFT)
        val admin = ServerProfileAdmin(fixture.repository)

        assertNull(admin.load(profile.id))
    }

    @Test
    fun staleDeviceMetadataCannotTurnAnApiKeyIntoADisconnectTarget() {
        val fixture = Fixture()
        val profile = fixture.repository.saveVerified(DRAFT)
        fixture.deviceMetadata[profile.id] = METADATA
        val admin = ServerProfileAdmin(fixture.repository)

        assertTrue("stale metadata is present", fixture.repository.hasDeviceCredential(profile.id))
        assertNull("API key must never reach self-revoke", admin.load(profile.id))
    }

    @Test
    fun hasDeviceCredentialReportsOnlyTheNonSecretMetadataPresence() {
        val fixture = Fixture()
        val profile = fixture.repository.saveVerifiedPaired(DRAFT, CREDENTIAL)

        assertTrue(fixture.repository.hasDeviceCredential(profile.id))

        fixture.repository.delete(profile.id)
        assertFalse(fixture.repository.hasDeviceCredential(profile.id))
        assertFalse(
            "unknown profile is never a device profile",
            fixture.repository.hasDeviceCredential("unknown-id")
        )
    }

    private fun assertRepositoryFailure(action: () -> Unit): ServerProfileRepositoryException {
        return try {
            action()
            fail("Expected ServerProfileRepositoryException")
            throw AssertionError("unreachable")
        } catch (error: ServerProfileRepositoryException) {
            error
        } catch (_: Throwable) {
            fail("Expected ServerProfileRepositoryException")
            throw AssertionError("unreachable")
        }
    }

    private fun SecretValue.useRawToString(): String {
        var raw = ""
        useRaw { raw = it }
        return raw
    }

    private class Fixture(
        failSaver: Boolean = false,
        failMetadataSave: Boolean = false,
        failMetadataAfterCommit: Boolean = false,
        wireSaver: Boolean = true
    ) {
        val events = mutableListOf<String>()
        val credentials = mutableMapOf<String, SecretValue>()
        val deviceMetadata = mutableMapOf<String, DeviceCredentialMetadata>()
        val metadata = FakeMetadataStore(events)
        val credentialStore = FakeCredentialStore(events, credentials)
        val deviceMetadataStore = FakeDeviceMetadataStore(events, deviceMetadata)

        val repository: ServerProfileRepository

        init {
            metadata.failSave = failMetadataSave
            metadata.failAfterNextSave = failMetadataAfterCommit
            repository = ServerProfileRepository(
                metadataStore = metadata,
                credentialStore = credentialStore,
                idFactory = { FIXTURE_ID },
                deviceCredentialSaver = if (wireSaver) {
                    FakeDeviceCredentialSaver(events, credentialStore, deviceMetadataStore, failSaver)
                } else {
                    null
                },
                deviceCredentialMetadataStore = if (wireSaver) deviceMetadataStore else null
            )
        }
    }

    private class FakeMetadataStore(
        private val events: MutableList<String>
    ) : ServerProfileMetadataStore {
        val profiles = linkedMapOf<String, ServerProfile>()
        var failSave = false
        var failAfterNextSave = false
        var failDelete = false

        override fun save(profile: ServerProfile) {
            events += "metadata.save"
            if (failSave) throw IllegalStateException("synthetic metadata failure")
            profiles[profile.id] = profile
            if (failAfterNextSave) {
                failAfterNextSave = false
                throw IllegalStateException("synthetic post-commit metadata failure")
            }
        }

        override fun get(profileId: String): ServerProfile? {
            events += "metadata.get"
            return profiles[profileId]
        }

        override fun list(): List<ServerProfile> {
            events += "metadata.list"
            return profiles.values.toList()
        }

        override fun delete(profileId: String) {
            events += "metadata.delete"
            if (failDelete) throw IllegalStateException("synthetic metadata delete failure")
            profiles.remove(profileId)
        }
    }

    private class FakeCredentialStore(
        private val events: MutableList<String>,
        private val values: MutableMap<String, SecretValue>
    ) : CredentialStore {
        var failDeleteNow = false

        override fun put(profileId: String, secret: SecretValue) {
            events += "credential.put"
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            events += "credential.get"
            return values[profileId]
        }

        override fun delete(profileId: String) {
            events += "credential.delete"
            if (failDeleteNow) throw IllegalStateException("synthetic credential delete failure")
            values.remove(profileId)
        }
    }

    private class FakeDeviceMetadataStore(
        private val events: MutableList<String>,
        private val values: MutableMap<String, DeviceCredentialMetadata>
    ) : DeviceCredentialMetadataStore {
        var failDeleteNow = false

        override fun put(profileId: String, metadata: DeviceCredentialMetadata) {
            events += "device.put"
            values[profileId] = metadata
        }

        override fun get(profileId: String): DeviceCredentialMetadata? {
            events += "device.get"
            return values[profileId]
        }

        override fun delete(profileId: String) {
            events += "device.delete"
            if (failDeleteNow) throw IllegalStateException("synthetic device metadata delete failure")
            values.remove(profileId)
        }
    }

    /**
     * Test double for the production saver: stores the device key through
     * the credential store and the non-secret metadata through the device
     * metadata store — the same composition as
     * [io.github.excludebarrier.orsikrelay.data.device.CredentialStoreDeviceCredentialSaver].
     */
    private class FakeDeviceCredentialSaver(
        private val events: MutableList<String>,
        private val credentialStore: CredentialStore,
        private val deviceMetadataStore: DeviceCredentialMetadataStore,
        private val fail: Boolean
    ) : DeviceCredentialSaver {
        override fun saveDeviceCredential(profile: ServerProfile, credential: DeviceCredential) {
            events += "device.save"
            if (fail) throw IllegalStateException("synthetic saver failure")
            credentialStore.put(profile.id, credential.deviceKey)
            deviceMetadataStore.put(
                profile.id,
                DeviceCredentialMetadata(
                    deviceId = credential.deviceId,
                    profile = credential.profile,
                    label = credential.label,
                    permissions = credential.permissions,
                    issuedAt = credential.issuedAt
                )
            )
        }
    }

    private companion object {
        const val FIXTURE_ID = "paired-fixture-id"
        val RAW_KEY = "hd_" + "B".repeat(43) // synthetic 46-char device key fixture
        const val API_KEY = "fixture-api-key"
        val DRAFT = ProfileDraft(
            profileName = "Fixture",
            baseUrl = "https://example.invalid/p/test",
            apiKey = SecretValue(API_KEY)
        )
        val METADATA = DeviceCredentialMetadata(
            deviceId = "dev-fixture",
            profile = "default",
            label = "fixture-label",
            permissions = listOf("chat"),
            issuedAt = 1.0
        )
        val CREDENTIAL = DeviceCredential(
            deviceId = METADATA.deviceId,
            deviceKey = SecretValue(RAW_KEY),
            profile = METADATA.profile,
            label = METADATA.label,
            permissions = METADATA.permissions,
            issuedAt = METADATA.issuedAt
        )
    }
}
