package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStopRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val transport = RunStopTransport { baseUrl, key, run ->
            calls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(RUN_ID, run)
            RunStopResult.Accepted(RUN_ID)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.stopRun(PROFILE_ID, RUN_ID) }

        assertEquals(RunStopResult.Accepted(RUN_ID), result)
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
        val transport = RunStopTransport { _, _, _ ->
            transportThread.set(Thread.currentThread())
            RunStopResult.Accepted(RUN_ID)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.stopRun(PROFILE_ID, RUN_ID) }

        assertEquals(RunStopResult.Accepted(RUN_ID), result)
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
            RunStopResult.Accepted(RUN_ID)
        }

        val result = runBlocking { repository.stopRun(PROFILE_ID, RUN_ID) }

        assertFailure(result, RunStopError.InvalidInput)
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
                RunStopResult.Accepted(RUN_ID)
            }

            val result = runBlocking { repository.stopRun(PROFILE_ID, RUN_ID) }

            assertFailure(result, RunStopError.InvalidInput)
            assertEquals(0, calls)
        }
    }

    @Test
    fun storeFailuresStayClosedAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = runBlocking {
            repository(metadata, credentials).stopRun(PROFILE_ID, RUN_ID)
        }

        assertFailure(result, RunStopError.InvalidInput)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))

        val failingCredentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        ).apply { failReads = true }
        val result2 = runBlocking {
            repository(
                FakeMetadataStore(mutableListOf(profile())),
                failingCredentials
            ).stopRun(PROFILE_ID, RUN_ID)
        }
        assertFailure(result2, RunStopError.InvalidInput)
        assertFalse(result2.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun transportFailureIsPreservedAndThrownFailuresMapToClosedNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val conflict = RunStopResult.Failure(RunStopError.Conflict)

        val preserved = runBlocking {
            repository(metadata, credentials) { _, _, _ -> conflict }
                .stopRun(PROFILE_ID, RUN_ID)
        }
        assertEquals(conflict, preserved)

        val thrown = runBlocking {
            repository(metadata, credentials) { _, _, _ ->
                throw IllegalStateException("synthetic-sensitive-detail")
            }.stopRun(PROFILE_ID, RUN_ID)
        }
        assertFailure(thrown, RunStopError.Network)
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
                RunStopResult.Accepted(RUN_ID)
            }

            val result = runBlocking { repository.stopRun(profileId, RUN_ID) }

            assertFailure(result, RunStopError.InvalidInput)
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
                RunStopResult.Accepted(RUN_ID)
            }

            val result = runBlocking { repository.stopRun(PROFILE_ID, runId) }

            assertFailure(result, RunStopError.InvalidInput)
            assertTrue(metadata.calls.isEmpty())
            assertTrue(credentials.calls.isEmpty())
            assertEquals(0, calls)
        }
    }

    @Test
    fun alreadyTerminalSuccessIsPropagatedWithoutDetails() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = RunStopTransport { _, _, _ ->
            RunStopResult.AlreadyTerminal(RUN_ID)
        }

        val result = runBlocking {
            repository(metadata, credentials, transport)
                .stopRun(PROFILE_ID, RUN_ID)
        }

        assertEquals(RunStopResult.AlreadyTerminal(RUN_ID), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun assertFailure(result: RunStopResult, expected: RunStopError) {
        assertTrue(result is RunStopResult.Failure)
        assertEquals(expected, (result as RunStopResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        transport: RunStopTransport = RunStopTransport { _, _, _ ->
            RunStopResult.Accepted(RUN_ID)
        }
    ): RunStopRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return RunStopRepository(profileRepository, credentials, transport)
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
