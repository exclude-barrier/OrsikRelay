package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.message.MessageFetcher
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistoryRepositoryTest {

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var fetchCalls = 0
        val fetcher = MessageFetcher { baseUrl, key, sessionId, offset ->
            fetchCalls += 1
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(SESSION_ID, sessionId)
            assertEquals(0, offset)
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }
        val repository = repository(metadata, credentials, fetcher)

        val result = repository.loadHistory(PROFILE_ID, SESSION_ID)

        assertTrue(result is MessageHistoryResult.Success)
        assertEquals(listOf(PROFILE_ID), metadata.getCalls)
        assertEquals(listOf(PROFILE_ID), credentials.getCalls)
        assertEquals(1, fetchCalls)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun olderHistoryDelegatesTheExactOffsetAndPreservesTheResult() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var fetchedOffset = -1
        val olderPage = MessagePage(
            messages = emptyList(),
            limit = 200,
            returned = 3
        )
        val fetcher = MessageFetcher { baseUrl, key, sessionId, offset ->
            assertEquals(BASE_URL, baseUrl)
            assertEquals(FIXTURE_KEY, key.useRaw { it })
            assertEquals(SESSION_ID, sessionId)
            fetchedOffset = offset
            MessageHistoryResult.Success(olderPage)
        }

        val result = repository(metadata, credentials, fetcher)
            .loadOlderHistory(PROFILE_ID, SESSION_ID, 200)

        assertEquals(200, fetchedOffset)
        assertEquals(MessageHistoryResult.Success(olderPage), result)
    }

    @Test
    fun olderFailureIsPreservedWithoutReadingAnotherProfile() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val expected = MessageHistoryResult.Failure(MessageHistoryError.RateLimited)

        val result = repository(metadata, credentials) { _, _, _, _ -> expected }
            .loadOlderHistory(PROFILE_ID, SESSION_ID, 0)

        assertEquals(expected, result)
        assertEquals(listOf(PROFILE_ID), metadata.getCalls)
        assertEquals(listOf(PROFILE_ID), credentials.getCalls)
    }

    @Test
    fun blankProfileIdIsRejectedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )

        val result = repository(metadata, credentials).loadHistory("  ", SESSION_ID)

        assertFailure(result, MessageHistoryError.MissingProfile)
        assertTrue(metadata.getCalls.isEmpty())
        assertTrue(credentials.getCalls.isEmpty())
    }

    @Test
    fun blankSessionIdIsRejectedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )

        val result = repository(metadata, credentials).loadHistory(PROFILE_ID, "")

        assertFailure(result, MessageHistoryError.InvalidInput)
        assertTrue(metadata.getCalls.isEmpty())
        assertTrue(credentials.getCalls.isEmpty())
    }

    @Test
    fun missingMetadataIsGenericProfileFailureAndDoesNotReadCredential() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        var fetchCalls = 0
        val repository = repository(metadata, credentials) { _, _, _, _ ->
            fetchCalls += 1
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }

        val result = repository.loadHistory(PROFILE_ID, SESSION_ID)

        assertFailure(result, MessageHistoryError.MissingProfile)
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
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }

        val result = repository.loadHistory(PROFILE_ID, SESSION_ID)

        assertFailure(result, MessageHistoryError.MissingProfile)
        assertEquals(0, fetchCalls)
    }

    @Test
    fun storeFailuresRemainGenericAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val result = repository(metadata, credentials).loadHistory(PROFILE_ID, SESSION_ID)

        assertFailure(result, MessageHistoryError.MissingProfile)
        assertFalse(result.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun fetcherResultIsPreservedAndThrownFailuresMapToNetwork() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val authFailure = MessageHistoryResult.Failure(MessageHistoryError.InvalidCredentials)

        val preserved = repository(metadata, credentials) { _, _, _, _ -> authFailure }
            .loadHistory(PROFILE_ID, SESSION_ID)
        assertEquals(authFailure, preserved)

        val thrown = repository(metadata, credentials) { _, _, _, _ ->
            throw IllegalStateException("synthetic-sensitive-detail")
        }.loadHistory(PROFILE_ID, SESSION_ID)
        assertFailure(thrown, MessageHistoryError.Network)
        assertFalse(thrown.toString().contains("synthetic-sensitive-detail"))
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        fetcher: MessageFetcher = MessageFetcher { _, _, _, _ ->
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }
    ): MessageHistoryRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return MessageHistoryRepository(profileRepository, credentials, fetcher)
    }

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private fun assertFailure(result: MessageHistoryResult, expected: MessageHistoryError) {
        assertTrue(result is MessageHistoryResult.Failure)
        assertEquals(expected, (result as MessageHistoryResult.Failure).error)
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
        const val SESSION_ID = "fixture-session-id"
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
