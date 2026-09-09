package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSteerRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val transport = RunSteerTransport { baseUrl, key, run, text ->
            calls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(RUN_ID, run)
            // The repository passes the user text through unchanged;
            // trimming happens in the transport layer.
            assertEquals(STEER_TEXT, text)
            RunSteerResult.Queued(RUN_ID)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.steerRun(PROFILE_ID, RUN_ID, STEER_TEXT) }

        assertEquals(RunSteerResult.Queued(RUN_ID), result)
        assertEquals(1, calls)
        assertEquals(listOf(PROFILE_ID), metadata.calls)
        assertEquals(listOf(PROFILE_ID), credentials.calls)
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(STEER_TEXT))
    }

    @Test
    fun transportRunsOnIoDispatcherNotOnTheCallingThread() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transportThread =
            java.util.concurrent.atomic.AtomicReference<Thread>()
        val transport = RunSteerTransport { _, _, _, _ ->
            transportThread.set(Thread.currentThread())
            RunSteerResult.Queued(RUN_ID)
        }
        val repository = repository(metadata, credentials, transport)

        val result = runBlocking { repository.steerRun(PROFILE_ID, RUN_ID, STEER_TEXT) }

        assertEquals(RunSteerResult.Queued(RUN_ID), result)
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
        val repository = repository(metadata, credentials) { _, _, _, _ ->
            calls += 1
            RunSteerResult.Queued(RUN_ID)
        }

        val result = runBlocking { repository.steerRun(PROFILE_ID, RUN_ID, STEER_TEXT) }

        assertFailure(result, RunSteerError.InvalidInput)
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
            val repository = repository(metadata, credentials) { _, _, _, _ ->
                calls += 1
                RunSteerResult.Queued(RUN_ID)
            }

            val result = runBlocking { repository.steerRun(PROFILE_ID, RUN_ID, STEER_TEXT) }

            assertFailure(result, RunSteerError.InvalidInput)
            assertEquals(0, calls)
        }
    }

    @Test
    fun storeFailuresStayClosedAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = runBlocking {
            repository(metadata, credentials).steerRun(PROFILE_ID, RUN_ID, STEER_TEXT)
        }

        assertFailure(result, RunSteerError.InvalidInput)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))

        val failingCredentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        ).apply { failReads = true }
        val result2 = runBlocking {
            repository(
                FakeMetadataStore(mutableListOf(profile())),
                failingCredentials
            ).steerRun(PROFILE_ID, RUN_ID, STEER_TEXT)
        }
        assertFailure(result2, RunSteerError.InvalidInput)
        assertFalse(result2.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun closedTransportResultsArePreservedAndThrownFailuresMapToClosedNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )

        listOf(
            RunSteerResult.Queued(RUN_ID),
            RunSteerResult.NotAccepting(RUN_ID),
            RunSteerResult.Failure(RunSteerError.RateLimited),
            RunSteerResult.Failure(RunSteerError.IncompatibleApi)
        ).forEach { transportResult ->
            val preserved = runBlocking {
                repository(metadata, credentials) { _, _, _, _ -> transportResult }
                    .steerRun(PROFILE_ID, RUN_ID, STEER_TEXT)
            }
            assertEquals(transportResult, preserved)
            assertFalse(preserved.toString().contains(FIXTURE_KEY))
            assertFalse(preserved.toString().contains(STEER_TEXT))
        }

        val thrown = runBlocking {
            repository(metadata, credentials) { _, _, _, _ ->
                throw IllegalStateException("synthetic-sensitive-detail")
            }.steerRun(PROFILE_ID, RUN_ID, STEER_TEXT)
        }
        assertFailure(thrown, RunSteerError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun blankProfileIdRunIdOrTextIsRejectedBeforeAnyStoreOrTransportAccess() {
        listOf("", "   ", "\t ").forEach { profileId ->
            assertRejectedBeforeAccess(
                profileId = profileId,
                runId = RUN_ID,
                text = STEER_TEXT
            )
        }
        listOf("", "   ", "\t ").forEach { runId ->
            assertRejectedBeforeAccess(
                profileId = PROFILE_ID,
                runId = runId,
                text = STEER_TEXT
            )
        }
        listOf("", "   ", "\n\t ").forEach { text ->
            assertRejectedBeforeAccess(
                profileId = PROFILE_ID,
                runId = RUN_ID,
                text = text
            )
        }
    }

    private fun assertRejectedBeforeAccess(
        profileId: String,
        runId: String,
        text: String
    ) {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var calls = 0
        val repository = repository(metadata, credentials) { _, _, _, _ ->
            calls += 1
            RunSteerResult.Queued(RUN_ID)
        }

        val result = runBlocking { repository.steerRun(profileId, runId, text) }

        assertFailure(result, RunSteerError.InvalidInput)
        assertTrue(metadata.calls.isEmpty())
        assertTrue(credentials.calls.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun notAcceptingIsPropagatedAsClosedOutcomeWithoutDetails() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = RunSteerTransport { _, _, _, _ ->
            RunSteerResult.NotAccepting(RUN_ID)
        }

        val result = runBlocking {
            repository(metadata, credentials, transport)
                .steerRun(PROFILE_ID, RUN_ID, STEER_TEXT)
        }

        assertEquals(RunSteerResult.NotAccepting(RUN_ID), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(STEER_TEXT))
    }

    private fun assertFailure(result: RunSteerResult, expected: RunSteerError) {
        assertTrue(result is RunSteerResult.Failure)
        assertEquals(expected, (result as RunSteerResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        transport: RunSteerTransport = RunSteerTransport { _, _, _, _ ->
            RunSteerResult.Queued(RUN_ID)
        }
    ): RunSteerRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return RunSteerRepository(profileRepository, credentials, transport)
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
        const val STEER_TEXT = "steer the run"
    }
}
