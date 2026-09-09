package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateFetcher
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCreateRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactInputs() {
        val profile = profile()
        val metadata = FakeMetadataStore(mutableListOf(profile))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        var calls = 0
        val fetcher = SessionCreateFetcher { baseUrl, key, title ->
            calls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals("Synthetischer Titel", title)
            SessionCreateResult.Success(CREATED_ID, "Synthetischer Titel")
        }
        val repository = repository(metadata, credentials, fetcher)

        val result = repository.createSession(PROFILE_ID, "  Synthetischer Titel  ")

        assertTrue(result is SessionCreateResult.Success)
        assertEquals(1, calls)
        assertEquals(listOf(PROFILE_ID), metadata.getCalls)
        assertEquals(listOf(PROFILE_ID), credentials.getCalls)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun blankTitleIsTreatedAsNoTitleAndSentAsNull() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        var calls = 0
        val seenTitles = mutableListOf<String?>()
        val fetcher = SessionCreateFetcher { _, _, title ->
            calls += 1
            seenTitles += title
            SessionCreateResult.Success(CREATED_ID, null)
        }
        val repository = repository(metadata, credentials, fetcher)

        listOf(null, "", "   ", "\t\n ").forEach { rawTitle ->
            val result = repository.createSession(PROFILE_ID, rawTitle)
            assertTrue(result is SessionCreateResult.Success)
        }

        assertEquals(4, calls)
        assertEquals(listOf(null, null, null, null), seenTitles)
    }

    @Test
    fun overlongTitleOrControlCharacterIsInvalidInputWithoutTransportCall() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        var calls = 0
        val fetcher = SessionCreateFetcher { _, _, _ ->
            calls += 1
            SessionCreateResult.Success(CREATED_ID, null)
        }
        val repository = repository(metadata, credentials, fetcher)

        listOf("x".repeat(513), "titel\nmit-steuerzeichen").forEach { title ->
            assertFailure(repository.createSession(PROFILE_ID, title), SessionCreateError.InvalidInput)
        }
        assertEquals(0, calls)
    }

    @Test
    fun maximumLengthTitleWithoutControlCharactersIsAccepted() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        var calls = 0
        val fetcher = SessionCreateFetcher { _, _, title ->
            calls += 1
            assertEquals(512, title?.length)
            SessionCreateResult.Success(CREATED_ID, null)
        }
        val repository = repository(metadata, credentials, fetcher)

        val result = repository.createSession(PROFILE_ID, "x".repeat(512))

        assertTrue(result is SessionCreateResult.Success)
        assertEquals(1, calls)
    }

    @Test
    fun missingMetadataIsGenericProfileFailureAndDoesNotReadCredential() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        var calls = 0
        val repository = repository(metadata, credentials) { _, _, _ ->
            calls += 1
            SessionCreateResult.Success(CREATED_ID, null)
        }

        val result = repository.createSession(PROFILE_ID, null)

        assertFailure(result, SessionCreateError.MissingProfile)
        assertTrue(credentials.getCalls.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun missingCredentialIsGenericProfileFailureAndDoesNotCallTransport() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore()
        var calls = 0
        val repository = repository(metadata, credentials) { _, _, _ ->
            calls += 1
            SessionCreateResult.Success(CREATED_ID, null)
        }

        val result = repository.createSession(PROFILE_ID, null)

        assertFailure(result, SessionCreateError.MissingProfile)
        assertEquals(0, calls)
    }

    @Test
    fun emptyCredentialIsGenericProfileFailureAndDoesNotCallTransport() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue("")))
        var calls = 0
        val repository = repository(metadata, credentials) { _, _, _ ->
            calls += 1
            SessionCreateResult.Success(CREATED_ID, null)
        }

        val result = repository.createSession(PROFILE_ID, null)

        assertFailure(result, SessionCreateError.MissingProfile)
        assertEquals(0, calls)
    }

    @Test
    fun storeFailuresRemainGenericAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = repository(metadata, credentials).createSession(PROFILE_ID, null)

        assertFailure(result, SessionCreateError.MissingProfile)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun fetcherResultIsPreservedAndThrownFailuresMapToNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        val conflict = SessionCreateResult.Failure(SessionCreateError.Conflict)

        val preserved = repository(metadata, credentials) { _, _, _ -> conflict }
            .createSession(PROFILE_ID, null)
        assertEquals(conflict, preserved)

        val thrown = repository(metadata, credentials) { _, _, _ ->
            throw IllegalStateException("synthetic-sensitive-detail")
        }.createSession(PROFILE_ID, null)
        assertFailure(thrown, SessionCreateError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun blankProfileIdIsRejectedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))

        val result = repository(metadata, credentials).createSession("  ", null)

        assertFailure(result, SessionCreateError.MissingProfile)
        assertTrue(metadata.getCalls.isEmpty())
        assertTrue(credentials.getCalls.isEmpty())
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        fetcher: SessionCreateFetcher = SessionCreateFetcher { _, _, _ ->
            SessionCreateResult.Success(CREATED_ID, null)
        }
    ): SessionCreateRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return SessionCreateRepository(profileRepository, credentials, fetcher)
    }

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private fun assertFailure(result: SessionCreateResult, expected: SessionCreateError) {
        assertTrue(result is SessionCreateResult.Failure)
        assertEquals(expected, (result as SessionCreateResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private class FakeMetadataStore(
        val profiles: MutableList<ServerProfile> = mutableListOf()
    ) : ServerProfileMetadataStore {
        val getCalls = mutableListOf<String>()
        var failReads = false

        override fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun get(profileId: String): ServerProfile? {
            getCalls += profileId
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
        val getCalls = mutableListOf<String>()

        override fun put(profileId: String, secret: SecretValue) {
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            getCalls += profileId
            return values[profileId]
        }

        override fun delete(profileId: String) {
            values.remove(profileId)
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
        const val CREATED_ID = "synthetic-created-id"
    }
}
