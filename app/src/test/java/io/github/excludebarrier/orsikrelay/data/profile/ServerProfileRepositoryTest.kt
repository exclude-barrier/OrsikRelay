package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
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

class ServerProfileRepositoryTest {

    @Test
    fun `saveVerified stores credential before separate metadata`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val credentials = FakeCredentialStore(events)
        val repository = repository(metadata, credentials)

        val profile = repository.saveVerified(draft())

        assertEquals(FIXED_ID, profile.id)
        assertEquals(FIXTURE_NAME, profile.profileName)
        assertEquals(FIXTURE_URL, profile.baseUrl)
        assertEquals(listOf("credential.put", "metadata.save"), events)
        assertEquals(profile, metadata.profiles[FIXED_ID])
        assertTrue(credentials.values[FIXED_ID]!!.useRaw { it == FIXTURE_SECRET })
    }

    @Test
    fun `credential failure never touches metadata`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val credentials = FakeCredentialStore(events).apply { failPut = true }
        val repository = repository(metadata, credentials)

        assertRepositoryFailure { repository.saveVerified(draft()) }

        assertEquals(listOf("credential.put"), events)
        assertTrue(metadata.profiles.isEmpty())
    }

    @Test
    fun `metadata failure rolls back credential`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events).apply { failSave = true }
        val credentials = FakeCredentialStore(events)
        val repository = repository(metadata, credentials)

        assertRepositoryFailure { repository.saveVerified(draft()) }

        assertEquals(
            listOf("credential.put", "metadata.save", "credential.delete"),
            events
        )
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun `metadata get and list never read credential`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[profile.id] = profile }
        val credentials = FakeCredentialStore(events)
        val repository = repository(metadata, credentials)

        assertEquals(profile, repository.get(FIXED_ID))
        assertEquals(listOf(profile), repository.list())

        assertEquals(listOf("metadata.get", "metadata.list"), events)
        assertEquals(0, credentials.getCalls)
    }

    @Test
    fun `credentialFor is the only explicit credential read`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val repository = repository(metadata, credentials)

        val loaded = repository.credentialFor(FIXED_ID)

        assertNotNull(loaded)
        assertTrue(loaded!!.useRaw { it == FIXTURE_SECRET })
        assertEquals(1, credentials.getCalls)
        assertEquals(listOf("credential.get"), events)
    }

    @Test
    fun `delete calls credential before metadata and is idempotent`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, clearer)

        repository.delete(FIXED_ID)
        repository.delete(FIXED_ID)

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete",
                "draft.clearProfile",
                "credential.delete",
                "metadata.delete",
                "draft.clearProfile"
            ),
            events
        )
        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertEquals(2, clearer.clearCalls)
        assertEquals(listOf(FIXED_ID, FIXED_ID), clearer.clearedProfileIds)
    }

    @Test
    fun `credential delete failure preserves drafts and uses the fixed boundary`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore(events).apply { failDelete = true }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, clearer)

        val error = assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertEquals(listOf("credential.delete"), events)
        assertTrue("metadata stays on a credential failure", metadata.profiles.containsKey(FIXED_ID))
        assertTrue("drafts stay on a credential failure", clearer.clearCalls == 0)
        assertEquals(
            ServerProfileRepositoryException.MESSAGE,
            error.message
        )
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun `metadata delete failure preserves drafts and uses the fixed boundary`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events).apply {
            profiles[FIXED_ID] = profile()
            failDelete = true
        }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, clearer)

        val error = assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertEquals(listOf("credential.delete", "metadata.delete"), events)
        assertTrue("drafts stay on a metadata failure", clearer.clearCalls == 0)
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun `failing draft cleaner never turns a successful deletion into a failure`() {
        val metadata = FakeMetadataStore(mutableListOf()).apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore(mutableListOf()).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val repository = repository(metadata, credentials, FailingProfileDraftClearer())

        // Must not throw: the profile is already deleted, and no underlying
        // exception detail may leak through the repository boundary.
        repository.delete(FIXED_ID)

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
    }

    @Test
    fun `delete without a draft cleaner keeps the previous behavior`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val repository = repository(metadata, credentials)

        repository.delete(FIXED_ID)

        assertEquals(listOf("credential.delete", "metadata.delete"), events)
        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
    }

    @Test
    fun `repository failure is fixed and contains no inputs`() {
        val metadata = FakeMetadataStore(mutableListOf())
        val credentials = FakeCredentialStore(mutableListOf()).apply { failPut = true }
        val repository = repository(metadata, credentials)

        val error = assertRepositoryFailure { repository.saveVerified(draft()) }

        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        listOf(FIXED_ID, FIXTURE_NAME, FIXTURE_URL, FIXTURE_SECRET).forEach { value ->
            assertFalse(error.toString().contains(value))
        }
    }

    @Test
    fun `injected deterministic id is used for both stores`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val credentials = FakeCredentialStore(events)

        repository(metadata, credentials).saveVerified(draft())

        assertTrue(metadata.profiles.containsKey(FIXED_ID))
        assertTrue(credentials.values.containsKey(FIXED_ID))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        profileDraftClearer: ProfileDraftClearer? = null
    ) = ServerProfileRepository(
        metadataStore = metadata,
        credentialStore = credentials,
        idFactory = { FIXED_ID },
        profileDraftClearer = profileDraftClearer
    )

    private fun draft() = ProfileDraft(
        profileName = FIXTURE_NAME,
        baseUrl = FIXTURE_URL,
        apiKey = SecretValue(FIXTURE_SECRET)
    )

    private fun profile() = ServerProfile.create(FIXED_ID, FIXTURE_NAME, FIXTURE_URL)

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

    private class FakeMetadataStore(
        private val events: MutableList<String>
    ) : ServerProfileMetadataStore {
        val profiles = linkedMapOf<String, ServerProfile>()
        var failSave = false
        var failDelete = false

        override fun save(profile: ServerProfile) {
            events += "metadata.save"
            if (failSave) throw IllegalStateException("synthetic metadata failure")
            profiles[profile.id] = profile
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
        private val events: MutableList<String>
    ) : CredentialStore {
        val values = linkedMapOf<String, SecretValue>()
        var failPut = false
        var failDelete = false
        var getCalls = 0

        override fun put(profileId: String, secret: SecretValue) {
            events += "credential.put"
            if (failPut) throw IllegalStateException("synthetic credential failure")
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            events += "credential.get"
            getCalls += 1
            return values[profileId]
        }

        override fun delete(profileId: String) {
            events += "credential.delete"
            if (failDelete) throw IllegalStateException("synthetic credential delete failure")
            values.remove(profileId)
        }
    }

    private class RecordingProfileDraftClearer(
        private val events: MutableList<String>
    ) : ProfileDraftClearer {
        var clearCalls = 0
        val clearedProfileIds = mutableListOf<String>()

        override fun clearProfile(profileId: String) {
            clearCalls++
            clearedProfileIds += profileId
            events += "draft.clearProfile"
        }
    }

    private class FailingProfileDraftClearer : ProfileDraftClearer {
        override fun clearProfile(profileId: String) {
            throw IllegalStateException("synthetic draft cleanup failure")
        }
    }

    private companion object {
        const val FIXED_ID = "fixture-id"
        const val FIXTURE_NAME = "Fixture"
        const val FIXTURE_URL = "https://example.invalid/p/test"
        const val FIXTURE_SECRET = "fixture-credential"
    }
}
