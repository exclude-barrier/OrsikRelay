package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStartupProfileResolverTest {

    @Test
    fun noStoredProfileKeepsOnboarding() {
        assertEquals(
            StartupProfile.None,
            resolveStartupProfile(FakeMetadataStore(), FakeCredentialStore())
        )
    }

    @Test
    fun selectsFirstCompleteProfileDeterministicallyById() {
        val later = profile("z-profile")
        val first = profile("a-profile")
        val metadata = FakeMetadataStore(listOf(later, first))
        val credentials = FakeCredentialStore(
            mapOf(
                later.id to SecretValue("fixture-z"),
                first.id to SecretValue("fixture-a")
            )
        )

        assertEquals(
            StartupProfile.Ready(first.id),
            resolveStartupProfile(metadata, credentials)
        )
    }

    @Test
    fun skipsMetadataWithoutCredentialAndUsesNextCompleteProfile() {
        val incomplete = profile("a-profile")
        val complete = profile("b-profile")
        val metadata = FakeMetadataStore(listOf(complete, incomplete))
        val credentials = FakeCredentialStore(
            mapOf(complete.id to SecretValue("fixture-b"))
        )

        assertEquals(
            StartupProfile.Ready(complete.id),
            resolveStartupProfile(metadata, credentials)
        )
    }

    @Test
    fun storedButIncompleteProfilesAreUnavailable() {
        val metadata = FakeMetadataStore(listOf(profile("a-profile")))

        assertEquals(
            StartupProfile.Unavailable,
            resolveStartupProfile(metadata, FakeCredentialStore())
        )
    }

    @Test
    fun metadataAndCredentialReadFailuresAreGeneric() {
        val failedMetadata = FakeMetadataStore(emptyList(), fail = true)
        assertEquals(
            StartupProfile.Unavailable,
            resolveStartupProfile(failedMetadata, FakeCredentialStore())
        )

        val profile = profile("a-profile")
        val failedCredential = FakeCredentialStore(emptyMap(), fail = true)
        assertEquals(
            StartupProfile.Unavailable,
            resolveStartupProfile(FakeMetadataStore(listOf(profile)), failedCredential)
        )
    }

    private fun profile(id: String) = ServerProfile.create(
        id = id,
        profileName = "Fixture",
        baseUrl = "https://example.invalid"
    )

    private class FakeMetadataStore(
        private val profiles: List<ServerProfile> = emptyList(),
        private val fail: Boolean = false
    ) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) = Unit
        override fun get(profileId: String): ServerProfile? = profiles.firstOrNull { it.id == profileId }
        override fun list(): List<ServerProfile> {
            if (fail) throw IllegalStateException("synthetic-sensitive-detail")
            return profiles
        }
        override fun delete(profileId: String) = Unit
    }

    private class FakeCredentialStore(
        private val values: Map<String, SecretValue> = emptyMap(),
        private val fail: Boolean = false
    ) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String): SecretValue? {
            if (fail) throw IllegalStateException("synthetic-sensitive-detail")
            return values[profileId]
        }
        override fun delete(profileId: String) = Unit
    }
}
