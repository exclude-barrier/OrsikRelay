package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManageRepositoryTest {

    private class RecordingTransport : SessionManageTransport {
        val calls = mutableListOf<String>()
        override fun supportsManagement(baseUrl: String, apiKey: SecretValue): Boolean {
            calls += "caps:$baseUrl:${apiKey.useRaw { it }}"
            return true
        }
        override fun setArchived(baseUrl: String, apiKey: SecretValue, sessionId: String, archived: Boolean): SessionManageResult {
            calls += "archive:$sessionId:$archived"
            return SessionManageResult.Success
        }
        override fun delete(baseUrl: String, apiKey: SecretValue, sessionId: String): SessionManageResult {
            calls += "delete:$sessionId"
            return SessionManageResult.Success
        }
    }

    private fun repository(
        transport: SessionManageTransport,
        profiles: MutableList<ServerProfile> = mutableListOf(ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)),
        credentials: MutableMap<String, SecretValue> = mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
    ): SessionManageRepository {
        val credentialStore = FakeCredentialStore(credentials)
        return SessionManageRepository(
            ServerProfileRepository(FakeMetadataStore(profiles), credentialStore, idFactory = { "unused-id" }),
            credentialStore,
            transport
        )
    }

    @Test
    fun delegatesWithTheProfilesUrlAndKey() {
        val transport = RecordingTransport()
        val repository = repository(transport)

        assertTrue(repository.supportsManagement(PROFILE_ID))
        assertEquals(SessionManageResult.Success, repository.setArchived(PROFILE_ID, "s1", archived = true))
        assertEquals(SessionManageResult.Success, repository.delete(PROFILE_ID, "s1"))
        assertEquals(listOf("caps:$BASE_URL:$FIXTURE_KEY", "archive:s1:true", "delete:s1"), transport.calls)
    }

    @Test
    fun aMissingProfileOrCredentialFailsClosedWithoutARequest() {
        val transport = RecordingTransport()
        listOf(
            repository(transport, profiles = mutableListOf()),
            repository(transport, credentials = mutableMapOf()),
            repository(transport, credentials = mutableMapOf(PROFILE_ID to SecretValue("")))
        ).forEach { repository ->
            assertFalse(repository.supportsManagement(PROFILE_ID))
            assertEquals(SessionManageResult.Failure(SessionManageError.Failed), repository.delete(PROFILE_ID, "s1"))
        }
        assertFalse(repository(transport).supportsManagement(" "))
        assertEquals(emptyList<String>(), transport.calls)
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
