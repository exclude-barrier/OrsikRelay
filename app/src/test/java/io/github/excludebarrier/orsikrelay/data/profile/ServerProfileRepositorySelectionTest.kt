package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
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
 * Mission 012: the [ServerProfileRepository] owns the persisted active
 * server selection.
 *
 * - [ServerProfileRepository.selectActiveServer] persists the selection only
 *   after the server exists (metadata AND credential), in the fixed order
 *   metadata-check, credential-check, store-write.
 * - [ServerProfileRepository.delete] removes the persisted selection only
 *   after credential, metadata and draft cleanup have ALL succeeded; any
 *   earlier failure preserves the selection and keeps the existing fixed
 *   failure boundary.
 */
class ServerProfileRepositorySelectionTest {

    @Test
    fun `selectActiveServer persists only for an existing complete server`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events)
        val repository = repository(metadata, credentials, selection)

        repository.selectActiveServer(FIXED_ID)

        assertEquals(
            listOf("metadata.get", "credential.get", "selection.write"),
            events
        )
        assertEquals(FIXED_ID, selection.value)
    }

    @Test
    fun `selectActiveServer rejects a server without metadata before any write`() {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val credentials = FakeCredentialStore(events)
        val selection = FakeSelectionStore(events)
        val repository = repository(metadata, credentials, selection)

        assertRepositoryFailure { repository.selectActiveServer("unknown-id") }

        assertEquals(listOf("metadata.get"), events)
        assertNull(selection.value)
        assertEquals(0, selection.writeCalls)
        assertEquals(0, credentials.getCalls)
    }

    @Test
    fun `selectActiveServer rejects a server without credential before any write`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events)
        val selection = FakeSelectionStore(events)
        val repository = repository(metadata, credentials, selection)

        assertRepositoryFailure { repository.selectActiveServer(FIXED_ID) }

        assertEquals(listOf("metadata.get", "credential.get"), events)
        assertNull(selection.value)
        assertEquals(0, selection.writeCalls)
    }

    @Test
    fun `selectActiveServer keeps the fixed failure boundary on a store write failure`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply { failWrite = true }
        val repository = repository(metadata, credentials, selection)

        val error = assertRepositoryFailure { repository.selectActiveServer(FIXED_ID) }

        assertEquals(listOf("metadata.get", "credential.get", "selection.write"), events)
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun `delete clears the selection only after the full verified cleanup`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, selection, clearer)

        repository.delete(FIXED_ID)

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete",
                "draft.clearProfile",
                "selection.read",
                "selection.clear"
            ),
            events
        )
        assertNull(selection.value)
        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
    }

    @Test
    fun `deleting another server keeps a valid selection intact`() {
        // Only deleting the SELECTED server removes the persisted selection.
        // Deleting a different profile runs the usual verified cleanup but
        // leaves the (still valid) selection untouched.
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply { value = "other-id" }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, selection, clearer)

        repository.delete(FIXED_ID)

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete",
                "draft.clearProfile",
                "selection.read"
            ),
            events
        )
        assertEquals("other-id", selection.value)
    }

    @Test
    fun `a credential delete failure preserves the selection and drafts`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            failDelete = true
        }
        val selection = FakeSelectionStore(events).apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, selection, clearer)

        assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertEquals(listOf("credential.delete"), events)
        assertEquals(FIXED_ID, selection.value)
        assertTrue("drafts stay", clearer.clearCalls == 0)
    }

    @Test
    fun `a metadata delete failure preserves the selection and drafts`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply {
            profiles[FIXED_ID] = profile
            failDelete = true
        }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, selection, clearer)

        assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertEquals(listOf("credential.delete", "metadata.delete"), events)
        assertEquals(FIXED_ID, selection.value)
        assertTrue("drafts stay", clearer.clearCalls == 0)
    }

    @Test
    fun `a draft cleanup failure preserves the selection but keeps the deletion successful`() {
        // Mission 009B contract: the deletion must stay successful even when
        // the cleaner misbehaves. Mission 012 adds: the selection of the
        // deleted profile is preserved, because the verified cleanup did not
        // complete — the next startup deterministically falls back to the
        // safe startup resolution.
        val metadata = FakeMetadataStore(mutableListOf()).apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore(mutableListOf()).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(mutableListOf()).apply { value = FIXED_ID }
        val repository = repository(
            metadata,
            credentials,
            selection,
            ProfileDraftClearer { throw IllegalStateException("synthetic draft cleanup failure") }
        )

        // Must not throw (009B contract): the profile is already deleted.
        repository.delete(FIXED_ID)

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertEquals("selection stays after a cleanup failure", FIXED_ID, selection.value)
    }

    @Test
    fun `a selection clear failure keeps the fixed boundary without leaking the id`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply {
            value = FIXED_ID
            failClear = true
        }
        val clearer = RecordingProfileDraftClearer(events)
        val repository = repository(metadata, credentials, selection, clearer)

        val error = assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete",
                "draft.clearProfile",
                "selection.read",
                "selection.clear"
            ),
            events
        )
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun `delete without a draft cleaner still clears the selection`() {
        val events = mutableListOf<String>()
        val profile = profile()
        val metadata = FakeMetadataStore(events).apply { profiles[FIXED_ID] = profile }
        val credentials = FakeCredentialStore(events).apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore(events).apply { value = FIXED_ID }
        val repository = repository(metadata, credentials, selection)

        repository.delete(FIXED_ID)

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete",
                "selection.read",
                "selection.clear"
            ),
            events
        )
        assertNull(selection.value)
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        selection: FakeSelectionStore,
        profileDraftClearer: ProfileDraftClearer? = null
    ) = ServerProfileRepository(
        metadataStore = metadata,
        credentialStore = credentials,
        idFactory = { FIXED_ID },
        profileDraftClearer = profileDraftClearer,
        selectionStore = selection
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
            if (failDelete) throw IllegalStateException("synthetic credential failure")
            values.remove(profileId)
        }
    }

    private class FakeSelectionStore(
        private val events: MutableList<String>
    ) : ActiveServerSelectionStore {
        var value: String? = null
        var writeCalls = 0
        var failWrite = false
        var failClear = false

        override fun read(): String? {
            events += "selection.read"
            return value
        }

        override fun write(profileId: String) {
            events += "selection.write"
            writeCalls++
            if (failWrite) throw IllegalStateException("synthetic selection failure")
            value = profileId
        }

        override fun clear() {
            events += "selection.clear"
            if (failClear) throw IllegalStateException("synthetic selection clear failure")
            value = null
        }
    }

    private class RecordingProfileDraftClearer(
        private val events: MutableList<String>
    ) : ProfileDraftClearer {
        var clearCalls = 0

        override fun clearProfile(profileId: String) {
            clearCalls++
            events += "draft.clearProfile"
        }
    }

    private companion object {
        const val FIXED_ID = "fixture-id"
        const val FIXTURE_NAME = "Fixture"
        const val FIXTURE_URL = "https://example.invalid/p/test"
        const val FIXTURE_SECRET = "fixture-credential"
    }
}
