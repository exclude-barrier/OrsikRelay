package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 014: repository contract of the approval decision. The profile
 * metadata and the credential must be loaded SEPARATELY by profile ID, the
 * single authenticated POST must be delegated with the exact arguments,
 * and every failure must stay closed (no profile data, URL, key, run ID,
 * request ID or exception text may escape). The decision is never retried.
 */
class RunApprovalRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val transport = RunApprovalTransport { baseUrl, key, run, requestId, choice ->
            calls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(RUN_ID, run)
            assertEquals(REQUEST_ID, requestId)
            assertEquals(RunApprovalChoice.ONCE, choice)
            RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE) }

        assertEquals(RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE), result)
        assertEquals(1, calls)
        assertEquals(listOf(PROFILE_ID), metadata.calls)
        assertEquals(listOf(PROFILE_ID), credentials.calls)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun transportRunsOnIoDispatcherNotOnTheCallingThread() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transportThread =
            java.util.concurrent.atomic.AtomicReference<Thread>()
        val transport = RunApprovalTransport { _, _, _, _, _ ->
            transportThread.set(Thread.currentThread())
            RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE) }

        assertEquals(RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE), result)
        val used = transportThread.get()
        assertTrue("transport was never invoked", used != null)
        assertNotSame(
            "transport must not run on the calling thread",
            Thread.currentThread(),
            used
        )
    }

    @Test
    fun missingMetadataIsClosedFailureAndSkipsCredentialRead() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val repository = repository(metadata, credentials) { _, _, _, _, _ ->
            calls += 1
            RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
        }

        val result = runBlocking { repository.respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE) }

        assertFailure(result, RunApprovalError.InvalidInput)
        assertEquals(listOf(PROFILE_ID), metadata.calls)
        assertTrue(credentials.calls.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun missingOrEmptyCredentialIsClosedFailureAndDoesNotCallTransport() {
        listOf(null, SecretValue("")).forEach { credential ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(
                if (credential == null) mutableMapOf()
                else mutableMapOf(PROFILE_ID to credential)
            )
            var calls = 0
            val repository = repository(metadata, credentials) { _, _, _, _, _ ->
                calls += 1
                RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
            }

            val result = runBlocking { repository.respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE) }

            assertFailure(result, RunApprovalError.InvalidInput)
            assertEquals(0, calls)
        }
    }

    @Test
    fun blankProfileIdRunIdOrRequestIdIsRejectedBeforeAnyStoreOrTransportAccess() {
        listOf("", "   ", "\t ").forEach { profileId ->
            val repository = repository(
                FakeMetadataStore(mutableListOf(profile())),
                FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
            )
            val result = runBlocking { repository.respond(profileId, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE) }
            assertFailure(result, RunApprovalError.InvalidInput)
        }
        listOf("", "   ", "\t ").forEach { runId ->
            val repository = repository(
                FakeMetadataStore(mutableListOf(profile())),
                FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
            )
            val result = runBlocking { repository.respond(PROFILE_ID, runId, REQUEST_ID, RunApprovalChoice.ONCE) }
            assertFailure(result, RunApprovalError.InvalidInput)
        }
        listOf("", "   ", "\t ").forEach { requestId ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
            var calls = 0
            val repository = repository(metadata, credentials) { _, _, _, _, _ ->
                calls += 1
                RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
            }
            val result = runBlocking { repository.respond(PROFILE_ID, RUN_ID, requestId, RunApprovalChoice.ONCE) }
            assertFailure(result, RunApprovalError.InvalidInput)
            assertTrue(metadata.calls.isEmpty())
            assertTrue(credentials.calls.isEmpty())
            assertEquals(0, calls)
        }
    }

    @Test
    fun storeFailuresStayClosedAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = runBlocking {
            repository(metadata, credentials).respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)
        }

        assertFailure(result, RunApprovalError.InvalidInput)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))

        val failingCredentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        ).apply { failReads = true }
        val result2 = runBlocking {
            repository(
                FakeMetadataStore(mutableListOf(profile())),
                failingCredentials
            ).respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)
        }
        assertFailure(result2, RunApprovalError.InvalidInput)
        assertFalse(result2.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun transportFailureIsPreservedAndThrownFailuresMapToClosedNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val noPending = RunApprovalResult.NoPending(RUN_ID)

        val preserved = runBlocking {
            repository(metadata, credentials) { _, _, _, _, _ -> noPending }
                .respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)
        }
        assertEquals(noPending, preserved)

        val thrown = runBlocking {
            repository(metadata, credentials) { _, _, _, _, _ ->
                throw IllegalStateException("synthetic-sensitive-detail")
            }.respond(PROFILE_ID, RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)
        }
        assertFailure(thrown, RunApprovalError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    private fun assertFailure(result: RunApprovalResult, expected: RunApprovalError) {
        assertTrue(result is RunApprovalResult.Failure)
        assertEquals(expected, (result as RunApprovalResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        transport: RunApprovalTransport = RunApprovalTransport { _, _, _, _, _ ->
            RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE)
        }
    ): RunApprovalRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return RunApprovalRepository(profileRepository, credentials, transport)
    }

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private class FakeMetadataStore(
        val profiles: MutableList<ServerProfile> = mutableListOf()
    ) : ServerProfileMetadataStore {
        val calls = mutableListOf<String>()
        var failReads = false

        override fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun get(profileId: String): ServerProfile? {
            calls += profileId
            if (failReads) throw IllegalStateException("synthetic-sensitive-detail")
            return profiles.firstOrNull { it.id == profileId }
        }

        override fun list(): List<ServerProfile> = profiles.sortedBy { it.id }

        override fun delete(profileId: String) {
            profiles.removeAll { it.id == profileId }
        }
    }

    private class FakeCredentialStore(
        val values: MutableMap<String, SecretValue> = mutableMapOf()
    ) : CredentialStore {
        val calls = mutableListOf<String>()
        var failReads = false

        override fun put(profileId: String, secret: SecretValue) {
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            calls += profileId
            if (failReads) throw IllegalStateException("synthetic-sensitive-detail")
            return values[profileId]
        }

        override fun delete(profileId: String) {
            values.remove(profileId)
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val RUN_ID = "run_synthetic"
        const val REQUEST_ID = "request_synthetic"
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
