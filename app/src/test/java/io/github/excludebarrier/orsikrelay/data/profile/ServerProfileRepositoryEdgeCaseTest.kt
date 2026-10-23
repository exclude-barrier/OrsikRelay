package io.github.excludebarrier.orsikrelay.data.profile

import org.junit.Assert.assertThrows
import io.github.excludebarrier.orsikrelay.data.device.DeviceCredentialMetadata
import io.github.excludebarrier.orsikrelay.data.device.DeviceCredentialMetadataStore
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Edge cases around partial store failures, active-profile deletion and
 * concurrent saves of [ServerProfileRepository].
 *
 * The repository must preserve its closed, input-free failure boundary on
 * every store failure and must keep drafts and the persisted selection
 * whenever an earlier deletion step failed (the documented order is
 * credential, metadata, drafts, selection).
 */
class ServerProfileRepositoryEdgeCaseTest {

    // -- deleting the active profile ----------------------------------------

    @Test
    fun deletingTheActiveProfileClearsMetadataCredentialDraftsAndSelection() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        repository.delete(FIXED_ID)

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertNull(selection.value)
        assertEquals(listOf(FIXED_ID), clearer.clearedProfileIds)
    }

    @Test
    fun aFailingDraftCleanerKeepsTheActiveProfileSelectionButCompletesTheDeletion() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer(fail = true)
        val repository = repository(metadata, credentials, selection, clearer)

        // Must not throw: the profile is already deleted.
        repository.delete(FIXED_ID)

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertEquals("selection stays after a cleanup failure", FIXED_ID, selection.value)
    }

    @Test
    fun aCredentialDeleteFailurePreservesMetadataDraftsAndSelection() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
            failDelete = true
        }
        val selection = FakeSelectionStore().apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        val error = assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertTrue(metadata.profiles.containsKey(FIXED_ID))
        assertTrue(credentials.values.containsKey(FIXED_ID))
        assertEquals(FIXED_ID, selection.value)
        assertTrue("drafts stay on a credential failure", clearer.clearedProfileIds.isEmpty())
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun aMetadataDeleteFailureAfterCredentialDeletionPreservesDraftsAndSelection() {
        val metadata = FakeMetadataStore().apply {
            profiles[FIXED_ID] = profile()
            failDelete = true
        }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertTrue(metadata.profiles.containsKey(FIXED_ID))
        assertTrue("credential was already deleted", credentials.values.isEmpty())
        assertEquals(FIXED_ID, selection.value)
        assertTrue("drafts stay on a metadata failure", clearer.clearedProfileIds.isEmpty())
    }

    @Test
    fun aMetadataDeleteThatCommitsThenThrowsStillPreservesDraftsAndSelection() {
        // Real stores can commit a delete and then report failure (for example
        // a failed post-commit bookkeeping step). The repository cannot know
        // the profile is gone, so it must keep the drafts and the selection.
        val metadata = FakeMetadataStore().apply {
            profiles[FIXED_ID] = profile()
            failDeleteAfterCommit = true
        }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply { value = FIXED_ID }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertFalse("the delete committed before throwing", metadata.profiles.containsKey(FIXED_ID))
        assertTrue(credentials.values.isEmpty())
        assertEquals(FIXED_ID, selection.value)
        assertTrue(clearer.clearedProfileIds.isEmpty())
    }

    @Test
    fun aSelectionReadFailureDoesNotTurnProfileDeletionIntoAFailure() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply {
            value = FIXED_ID
            failRead = true
        }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        repository.delete(FIXED_ID)

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertEquals("an unreadable selection cannot be cleared", FIXED_ID, selection.value)
        assertEquals(0, selection.clearCalls)
        assertEquals(listOf(FIXED_ID), clearer.clearedProfileIds)
    }

    @Test
    fun aSelectionClearFailureUsesTheFixedBoundaryWithoutLeakingTheId() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val selection = FakeSelectionStore().apply {
            value = FIXED_ID
            failClear = true
        }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        val error = assertRepositoryFailure { repository.delete(FIXED_ID) }

        assertNull(metadata.profiles[FIXED_ID])
        assertNull(credentials.values[FIXED_ID])
        assertEquals(listOf(FIXED_ID), clearer.clearedProfileIds)
        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXED_ID))
    }

    @Test
    fun deletingAnAlreadyMissingProfileIsIdempotentAndStillRunsCleanup() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        val selection = FakeSelectionStore().apply { value = OTHER_ID }
        val clearer = RecordingProfileDraftClearer()
        val repository = repository(metadata, credentials, selection, clearer)

        repository.delete(FIXED_ID)

        assertEquals(OTHER_ID, selection.value)
        assertEquals(listOf(FIXED_ID), clearer.clearedProfileIds)
    }

    // -- hasDeviceCredential failure contract --------------------------------

    @Test
    fun hasDeviceCredentialIsFalseWithoutADeviceMetadataStore() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore()

        val repository = repository(metadata, credentials)

        assertFalse(repository.hasDeviceCredential(FIXED_ID))
    }

    @Test
    fun hasDeviceCredentialNeverReadsTheCredential() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val deviceMetadata = FakeDeviceMetadataStore().apply {
            values[FIXED_ID] = deviceMetadata()
        }
        val credentials = FakeCredentialStore().apply { failGet = true }
        val repository = repository(
            metadata,
            credentials,
            deviceMetadata = deviceMetadata
        )

        assertTrue(repository.hasDeviceCredential(FIXED_ID))
        assertEquals("the credential probe must stay sealed", 0, credentials.getCalls)
    }

    @Test
    fun aDeviceMetadataFailureIsReportedNotTurnedIntoFalse() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val deviceMetadata = FakeDeviceMetadataStore().apply { failGet = true }
        val repository = repository(
            metadata,
            FakeCredentialStore(),
            deviceMetadata = deviceMetadata
        )

        // Fail closed (decided): a failure must not read as "API key", or a
        // device profile would be deleted without revoking its device key;
        // the profile screen treats the exception as "kind unknown".
        assertThrows(ServerProfileRepositoryException::class.java) { repository.hasDeviceCredential(FIXED_ID) }
    }

    @Test
    fun aProfileMetadataFailureIsReportedNotTurnedIntoFalse() {
        val metadata = FakeMetadataStore().apply { failGet = true }
        val repository = repository(metadata, FakeCredentialStore())

        // Fail closed (decided): a failure must not read as "API key", or a
        // device profile would be deleted without revoking its device key;
        // the profile screen treats the exception as "kind unknown".
        assertThrows(ServerProfileRepositoryException::class.java) { repository.hasDeviceCredential(FIXED_ID) }
    }

    // -- read failures -------------------------------------------------------

    @Test
    fun metadataReadFailuresUseTheFixedBoundary() {
        val metadata = FakeMetadataStore()
        val repository = repository(metadata, FakeCredentialStore())

        metadata.failGet = true
        val getError = assertRepositoryFailure { repository.get(FIXED_ID) }

        metadata.failGet = false
        metadata.failList = true
        val listError = assertRepositoryFailure { repository.list() }

        assertEquals(ServerProfileRepositoryException.MESSAGE, getError.message)
        assertEquals(ServerProfileRepositoryException.MESSAGE, listError.message)
        assertFalse(getError.toString().contains(FIXED_ID))
    }

    @Test
    fun aCredentialReadFailureUsesTheFixedBoundaryAndKeepsTheSecretInvisible() {
        val credentials = FakeCredentialStore().apply { failGet = true }
        val repository = repository(FakeMetadataStore(), credentials)

        val error = assertRepositoryFailure { repository.credentialFor(FIXED_ID) }

        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse(error.toString().contains(FIXTURE_SECRET))
        assertFalse(error.toString().contains(FIXED_ID))
    }

    // -- selection write failures -------------------------------------------

    @Test
    fun selectActiveServerFailsClosedWhenTheMetadataStoreFails() {
        val metadata = FakeMetadataStore().apply { failGet = true }
        val selection = FakeSelectionStore()
        val repository = repository(metadata, FakeCredentialStore(), selection)

        assertRepositoryFailure { repository.selectActiveServer(FIXED_ID) }

        assertEquals(0, selection.writeCalls)
    }

    @Test
    fun selectActiveServerFailsClosedWhenTheCredentialStoreFails() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply { failGet = true }
        val selection = FakeSelectionStore()
        val repository = repository(metadata, credentials, selection)

        assertRepositoryFailure { repository.selectActiveServer(FIXED_ID) }

        assertEquals(0, selection.writeCalls)
    }

    @Test
    fun selectActiveServerWithABlankIdFailsBeforeCredentialReadAndWrite() {
        val metadata = FakeMetadataStore().apply { rejectBlankIds = true }
        val credentials = FakeCredentialStore()
        val selection = FakeSelectionStore()
        val repository = repository(metadata, credentials, selection)

        assertRepositoryFailure { repository.selectActiveServer("  ") }

        assertEquals(0, credentials.getCalls)
        assertEquals(0, selection.writeCalls)
    }

    @Test
    fun selectActiveServerWithoutASelectionStoreFailsClosedAfterBothChecks() {
        val metadata = FakeMetadataStore().apply { profiles[FIXED_ID] = profile() }
        val credentials = FakeCredentialStore().apply {
            values[FIXED_ID] = SecretValue(FIXTURE_SECRET)
        }
        val repository = repository(metadata, credentials, selection = null)

        assertRepositoryFailure { repository.selectActiveServer(FIXED_ID) }

        assertEquals(1, credentials.getCalls)
    }

    // -- save failures -------------------------------------------------------

    @Test
    fun anUnnormalizedDraftIsRejectedBeforeAnyStoreIsTouched() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        val repository = repository(metadata, credentials)

        val error = assertRepositoryFailure {
            repository.saveVerified(
                ProfileDraft(
                    profileName = FIXTURE_NAME,
                    baseUrl = "https://example.invalid/",
                    apiKey = SecretValue(FIXTURE_SECRET)
                )
            )
        }

        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertTrue(metadata.profiles.isEmpty())
        assertTrue(credentials.values.isEmpty())
        assertEquals(0, credentials.putCalls)
    }

    @Test
    fun anIdFactoryFailureUsesTheFixedBoundaryAndTouchesNoStore() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        val repository = ServerProfileRepository(
            metadataStore = metadata,
            credentialStore = credentials,
            idFactory = { throw IllegalStateException("synthetic id failure") }
        )

        assertRepositoryFailure { repository.saveVerified(draft()) }

        assertTrue(metadata.profiles.isEmpty())
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun aMetadataSaveFailureRollsBackTheCredentialEvenWhenTheRollbackFails() {
        val metadata = FakeMetadataStore().apply { failSave = true }
        val credentials = FakeCredentialStore().apply { failDelete = true }
        val repository = repository(metadata, credentials)

        val error = assertRepositoryFailure { repository.saveVerified(draft()) }

        assertEquals(ServerProfileRepositoryException.MESSAGE, error.message)
        assertFalse("no raw secret in the exception", error.toString().contains(FIXTURE_SECRET))
        assertFalse("no underlying text in the exception", error.toString().contains("synthetic"))
        // The rollback is best effort: the orphan credential may remain, but
        // the failure boundary and the raw secret stay hidden.
        assertTrue(metadata.profiles.isEmpty())
        assertEquals(1, credentials.deleteCalls)
    }

    // -- concurrent saves ----------------------------------------------------

    @Test
    fun concurrentSavesOfDistinctProfilesPersistEveryProfileAndCredential() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        val ids = AtomicInteger()
        val repository = repository(
            metadata,
            credentials,
            idFactory = { "profile-${ids.incrementAndGet()}" }
        )
        val count = 24
        val start = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()

        val threads = (0 until count).map { index ->
            Thread {
                try {
                    start.await()
                    repository.saveVerified(
                        ProfileDraft(
                            profileName = "Concurrent-$index",
                            baseUrl = FIXTURE_URL,
                            apiKey = SecretValue("$FIXTURE_SECRET-$index")
                        )
                    )
                } catch (error: Throwable) {
                    errors += error
                }
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { thread ->
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("a concurrent save thread did not finish", thread.isAlive)
        }

        assertTrue("no concurrent save may fail: $errors", errors.isEmpty())
        assertEquals(count, metadata.profiles.size)
        assertEquals(count, credentials.values.size)
        assertEquals(count, repository.list().size)
        assertEquals(
            "every save produced a distinct profile",
            count,
            repository.list().map { it.id }.toSet().size
        )
    }

    // -- helpers -------------------------------------------------------------

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        selection: FakeSelectionStore? = null,
        clearer: RecordingProfileDraftClearer? = null,
        deviceMetadata: FakeDeviceMetadataStore? = null,
        idFactory: () -> String = { FIXED_ID }
    ) = ServerProfileRepository(
        metadataStore = metadata,
        credentialStore = credentials,
        idFactory = idFactory,
        profileDraftClearer = clearer,
        selectionStore = selection,
        deviceCredentialMetadataStore = deviceMetadata
    )

    private fun profile() = ServerProfile.create(FIXED_ID, FIXTURE_NAME, FIXTURE_URL)

    private fun draft() = ProfileDraft(
        profileName = FIXTURE_NAME,
        baseUrl = FIXTURE_URL,
        apiKey = SecretValue(FIXTURE_SECRET)
    )

    private fun deviceMetadata() = DeviceCredentialMetadata(
        deviceId = "device-fixture",
        profile = "default",
        label = "fixture-label",
        permissions = listOf("chat"),
        issuedAt = 1.0
    )

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

    private class FakeMetadataStore : ServerProfileMetadataStore {
        val profiles = ConcurrentHashMap<String, ServerProfile>()
        val events = CopyOnWriteArrayList<String>()
        val getIds = CopyOnWriteArrayList<String>()
        var failGet = false
        var failList = false
        var failSave = false
        var failDelete = false
        var failDeleteAfterCommit = false
        var rejectBlankIds = false

        override fun save(profile: ServerProfile) {
            events += "metadata.save"
            if (failSave) throw IllegalStateException("synthetic metadata save failure")
            profiles[profile.id] = profile
        }

        override fun get(profileId: String): ServerProfile? {
            events += "metadata.get"
            getIds += profileId
            if (rejectBlankIds && profileId.isBlank()) {
                throw IllegalStateException("synthetic blank id rejection")
            }
            if (failGet) throw IllegalStateException("synthetic metadata get failure")
            return profiles[profileId]
        }

        override fun list(): List<ServerProfile> {
            events += "metadata.list"
            if (failList) throw IllegalStateException("synthetic metadata list failure")
            return profiles.values.toList()
        }

        override fun delete(profileId: String) {
            events += "metadata.delete"
            if (failDelete) throw IllegalStateException("synthetic metadata delete failure")
            profiles.remove(profileId)
            if (failDeleteAfterCommit) {
                throw IllegalStateException("synthetic post-commit metadata failure")
            }
        }
    }

    private class FakeCredentialStore : CredentialStore {
        val values = ConcurrentHashMap<String, SecretValue>()
        var failPut = false
        var failGet = false
        var failDelete = false
        var putCalls = 0
        var getCalls = 0
        var deleteCalls = 0

        override fun put(profileId: String, secret: SecretValue) {
            putCalls++
            if (failPut) throw IllegalStateException("synthetic credential put failure")
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            getCalls++
            if (failGet) throw IllegalStateException("synthetic credential get failure")
            return values[profileId]
        }

        override fun delete(profileId: String) {
            deleteCalls++
            if (failDelete) throw IllegalStateException("synthetic credential delete failure")
            values.remove(profileId)
        }
    }

    private class FakeSelectionStore : ActiveServerSelectionStore {
        var value: String? = null
        var failRead = false
        var failWrite = false
        var failClear = false
        var writeCalls = 0
        var clearCalls = 0

        override fun read(): String? {
            if (failRead) throw ActiveServerSelectionStoreException()
            return value
        }

        override fun write(profileId: String) {
            writeCalls++
            if (failWrite) throw ActiveServerSelectionStoreException()
            if (profileId.isBlank()) throw ActiveServerSelectionStoreException()
            value = profileId
        }

        override fun clear() {
            clearCalls++
            if (failClear) throw ActiveServerSelectionStoreException()
            value = null
        }
    }

    private class FakeDeviceMetadataStore : DeviceCredentialMetadataStore {
        val values = ConcurrentHashMap<String, DeviceCredentialMetadata>()
        var failGet = false
        var failDelete = false

        override fun put(profileId: String, metadata: DeviceCredentialMetadata) {
            values[profileId] = metadata
        }

        override fun get(profileId: String): DeviceCredentialMetadata? {
            if (failGet) throw IllegalStateException("synthetic device metadata get failure")
            return values[profileId]
        }

        override fun delete(profileId: String) {
            if (failDelete) throw IllegalStateException("synthetic device metadata delete failure")
            values.remove(profileId)
        }
    }

    private class RecordingProfileDraftClearer(
        private val fail: Boolean = false
    ) : ProfileDraftClearer {
        val clearedProfileIds = CopyOnWriteArrayList<String>()

        override fun clearProfile(profileId: String) {
            clearedProfileIds += profileId
            if (fail) throw IllegalStateException("synthetic draft cleanup failure")
        }
    }

    private companion object {
        const val FIXED_ID = "fixture-id"
        const val OTHER_ID = "other-id"
        const val FIXTURE_NAME = "Fixture"
        const val FIXTURE_URL = "https://example.invalid/p/test"
        const val FIXTURE_SECRET = "fixture-credential"
    }
}
