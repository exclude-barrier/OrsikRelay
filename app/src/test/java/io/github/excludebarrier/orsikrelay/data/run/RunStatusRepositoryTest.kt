package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatusRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val transport = RunStatusTransport { baseUrl, key, run ->
            calls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(RUN_ID, run)
            RunStatusResult.Known(RunServerStatus.RUNNING)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.getRunStatus(PROFILE_ID, RUN_ID) }

        assertEquals(RunStatusResult.Known(RunServerStatus.RUNNING), result)
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
        val transportThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val transport = RunStatusTransport { _, _, _ ->
            transportThread.set(Thread.currentThread())
            RunStatusResult.Known(RunServerStatus.RUNNING)
        }
        val repository = repository(metadata, credentials, transport)

        runBlocking { repository.getRunStatus(PROFILE_ID, RUN_ID) }

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
        val repository = repository(metadata, credentials) { _, _, _ ->
            calls += 1
            RunStatusResult.Known(RunServerStatus.RUNNING)
        }

        val result = runBlocking { repository.getRunStatus(PROFILE_ID, RUN_ID) }

        assertFailure(result, RunStatusError.InvalidInput)
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
            val repository = repository(metadata, credentials) { _, _, _ ->
                calls += 1
                RunStatusResult.Known(RunServerStatus.RUNNING)
            }

            val result = runBlocking { repository.getRunStatus(PROFILE_ID, RUN_ID) }

            assertFailure(result, RunStatusError.InvalidInput)
            assertEquals(0, calls)
        }
    }

    @Test
    fun storeFailuresStayClosedAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = runBlocking {
            repository(metadata, credentials).getRunStatus(PROFILE_ID, RUN_ID)
        }

        assertFailure(result, RunStatusError.InvalidInput)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))

        val failingCredentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        ).apply { failReads = true }
        val result2 = runBlocking {
            repository(
                FakeMetadataStore(mutableListOf(profile())),
                failingCredentials
            ).getRunStatus(PROFILE_ID, RUN_ID)
        }
        assertFailure(result2, RunStatusError.InvalidInput)
        assertFalse(result2.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun transportFailureIsPreservedAndThrownFailuresMapToClosedNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val notFound = RunStatusResult.Failure(RunStatusError.NotFound)

        val preserved = runBlocking {
            repository(metadata, credentials) { _, _, _ -> notFound }
                .getRunStatus(PROFILE_ID, RUN_ID)
        }
        assertEquals(notFound, preserved)

        val thrown = runBlocking {
            repository(metadata, credentials) { _, _, _ ->
                throw IllegalStateException("synthetic-sensitive-detail")
            }.getRunStatus(PROFILE_ID, RUN_ID)
        }
        assertFailure(thrown, RunStatusError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun blankProfileIdOrRunIdIsRejectedBeforeAnyStoreOrTransportAccess() {
        listOf("", "   ", "\t ").forEach { profileId ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(
                mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
            )
            var calls = 0
            val repository = repository(metadata, credentials) { _, _, _ ->
                calls += 1
                RunStatusResult.Known(RunServerStatus.RUNNING)
            }

            val result = runBlocking { repository.getRunStatus(profileId, RUN_ID) }

            assertFailure(result, RunStatusError.InvalidInput)
            assertTrue(metadata.calls.isEmpty())
            assertTrue(credentials.calls.isEmpty())
            assertEquals(0, calls)
        }
        listOf("", "   ", "\t ").forEach { runId ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(
                mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
            )
            var calls = 0
            val repository = repository(metadata, credentials) { _, _, _ ->
                calls += 1
                RunStatusResult.Known(RunServerStatus.RUNNING)
            }

            val result = runBlocking { repository.getRunStatus(PROFILE_ID, runId) }

            assertFailure(result, RunStatusError.InvalidInput)
            assertTrue(metadata.calls.isEmpty())
            assertTrue(credentials.calls.isEmpty())
            assertEquals(0, calls)
        }
    }

    @Test
    fun terminalStatusIsPropagatedWithoutDetails() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = RunStatusTransport { _, _, _ ->
            RunStatusResult.Known(RunServerStatus.COMPLETED)
        }

        val result = runBlocking {
            repository(metadata, credentials, transport).getRunStatus(PROFILE_ID, RUN_ID)
        }

        assertEquals(RunStatusResult.Known(RunServerStatus.COMPLETED), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun assertFailure(result: RunStatusResult, expected: RunStatusError) {
        assertTrue(result is RunStatusResult.Failure)
        assertEquals(expected, (result as RunStatusResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        transport: RunStatusTransport = RunStatusTransport { _, _, _ ->
            RunStatusResult.Known(RunServerStatus.RUNNING)
        }
    ): RunStatusRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return RunStatusRepository(profileRepository, credentials, transport)
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
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
