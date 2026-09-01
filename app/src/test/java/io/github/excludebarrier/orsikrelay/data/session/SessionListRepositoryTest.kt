package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageFetcher
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactPage() {
        val profile = profile()
        val metadata = FakeMetadataStore(mutableListOf(profile))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        var fetchCalls = 0
        val fetcher = SessionPageFetcher { baseUrl, key, limit, offset ->
            fetchCalls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(25, limit)
            assertEquals(50, offset)
            SessionListResult.Success(SessionPage(emptyList(), hasMore = true))
        }
        val repository = repository(metadata, credentials, fetcher)

        val result = repository.fetchPage(PROFILE_ID, limit = 25, offset = 50)

        assertTrue(result is SessionListResult.Success)
        assertEquals(listOf(PROFILE_ID), metadata.getCalls)
        assertEquals(listOf(PROFILE_ID), credentials.getCalls)
        assertEquals(1, fetchCalls)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun missingMetadataIsGenericProfileFailureAndDoesNotReadCredential() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        var fetchCalls = 0
        val repository = repository(metadata, credentials) { _, _, _, _ ->
            fetchCalls += 1
            SessionListResult.Success(SessionPage(emptyList(), false))
        }

        val result = repository.fetchPage(PROFILE_ID, 50, 0)

        assertFailure(result, SessionListError.MissingProfile)
        assertTrue(credentials.getCalls.isEmpty())
        assertEquals(0, fetchCalls)
    }

    @Test
    fun missingCredentialIsGenericProfileFailureAndDoesNotFetch() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore()
        var fetchCalls = 0
        val repository = repository(metadata, credentials) { _, _, _, _ ->
            fetchCalls += 1
            SessionListResult.Success(SessionPage(emptyList(), false))
        }

        val result = repository.fetchPage(PROFILE_ID, 50, 0)

        assertFailure(result, SessionListError.MissingProfile)
        assertEquals(0, fetchCalls)
    }

    @Test
    fun storeFailuresRemainGenericAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = repository(metadata, credentials).fetchPage(PROFILE_ID, 50, 0)

        assertFailure(result, SessionListError.MissingProfile)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun fetcherResultIsPreservedAndThrownFailuresMapToNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))
        val authFailure = SessionListResult.Failure(SessionListError.InvalidCredentials)

        val preserved = repository(metadata, credentials) { _, _, _, _ -> authFailure }
            .fetchPage(PROFILE_ID, 50, 0)
        assertEquals(authFailure, preserved)

        val thrown = repository(metadata, credentials) { _, _, _, _ ->
            throw IllegalStateException("synthetic-sensitive-detail")
        }.fetchPage(PROFILE_ID, 50, 0)
        assertFailure(thrown, SessionListError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun blankProfileIdIsRejectedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY)))

        val result = repository(metadata, credentials).fetchPage("  ", 50, 0)

        assertFailure(result, SessionListError.MissingProfile)
        assertTrue(metadata.getCalls.isEmpty())
        assertTrue(credentials.getCalls.isEmpty())
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        fetcher: SessionPageFetcher = SessionPageFetcher { _, _, _, _ ->
            SessionListResult.Success(SessionPage(emptyList(), false))
        }
    ): SessionListRepository {
        val profileRepository = ServerProfileRepository(metadata, credentials) { "unused-id" }
        return SessionListRepository(profileRepository, credentials, fetcher)
    }

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private fun assertFailure(result: SessionListResult, expected: SessionListError) {
        assertTrue(result is SessionListResult.Failure)
        assertEquals(expected, (result as SessionListResult.Failure).error)
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
    }
}
