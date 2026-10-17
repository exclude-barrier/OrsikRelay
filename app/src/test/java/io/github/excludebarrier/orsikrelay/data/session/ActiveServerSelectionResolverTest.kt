package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerSelection
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The persisted active server selection is validated against
 * the existing metadata and credential stores. A missing, deleted or
 * inconsistent selection deterministically yields [ServerSelection.Invalid]
 * so the caller falls back to the existing safe startup state; no ID or
 * error detail may escape.
 */
class ActiveServerSelectionResolverTest {

    @Test
    fun aValidSelectionResolvesToTheStoredServer() {
        val profile = profile("profile-a")
        assertEquals(
            ServerSelection.Valid(profile.id),
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(profile.id),
                metadataStore = FakeMetadataStore(listOf(profile)),
                credentialStore = FakeCredentialStore(mapOf(profile.id to SecretValue("fixture-a")))
            )
        )
    }

    @Test
    fun anAbsentSelectionIsInvalid() {
        val profile = profile("profile-a")
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(null),
                metadataStore = FakeMetadataStore(listOf(profile)),
                credentialStore = FakeCredentialStore(mapOf(profile.id to SecretValue("fixture-a")))
            )
        )
    }

    @Test
    fun aDeletedServerIsInvalidEvenWhenTheMetadataListChanged() {
        val other = profile("profile-b")
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore("profile-deleted"),
                metadataStore = FakeMetadataStore(listOf(other)),
                credentialStore = FakeCredentialStore(mapOf(other.id to SecretValue("fixture-b")))
            )
        )
    }

    @Test
    fun aServerWithoutCredentialIsInvalid() {
        val profile = profile("profile-a")
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(profile.id),
                metadataStore = FakeMetadataStore(listOf(profile)),
                credentialStore = FakeCredentialStore()
            )
        )
    }

    @Test
    fun aBlankStoredIdIsInvalidAndNeverReachesTheCredentialStore() {
        val credentialStore = RecordingCredentialStore()
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore("   "),
                metadataStore = FakeMetadataStore(listOf(profile("profile-a"))),
                credentialStore = credentialStore
            )
        )
        assertEquals(0, credentialStore.getCalls)
    }

    @Test
    fun storeFailuresAreInvalidWithoutLeakingDetails() {
        val profile = profile("profile-a")
        val credentials = mapOf(profile.id to SecretValue("fixture-a"))
        val metadata = FakeMetadataStore(listOf(profile))
        val credentialStore = FakeCredentialStore(credentials)

        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(profile.id, failRead = true),
                metadataStore = metadata,
                credentialStore = credentialStore
            )
        )
        // The selection path reads the metadata by ID (get), not the list:
        // a metadata read failure makes the selection invalid.
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(profile.id),
                metadataStore = FakeMetadataStore(listOf(profile), failGet = true),
                credentialStore = credentialStore
            )
        )
        assertEquals(
            ServerSelection.Invalid,
            resolveActiveServerSelection(
                selectionStore = FakeSelectionStore(profile.id),
                metadataStore = metadata,
                credentialStore = FakeCredentialStore(credentials, failGet = true)
            )
        )
    }

    @Test
    fun theStartupOverloadPrefersAValidSelectionOverTheFirstProfile() {
        val first = profile("a-profile")
        val selected = profile("z-profile")
        val startup = resolveStartupProfile(
            metadataStore = FakeMetadataStore(listOf(first, selected)),
            credentialStore = FakeCredentialStore(
                mapOf(
                    first.id to SecretValue("fixture-a"),
                    selected.id to SecretValue("fixture-z")
                )
            ),
            selectionStore = FakeSelectionStore(selected.id)
        )

        assertEquals(StartupProfile.Ready(selected.id), startup)
    }

    @Test
    fun theStartupOverloadFallsBackToTheExistingResolutionOnAnInvalidSelection() {
        val first = profile("a-profile")
        val deleted = profile("z-profile")
        val startup = resolveStartupProfile(
            metadataStore = FakeMetadataStore(listOf(first)),
            credentialStore = FakeCredentialStore(mapOf(first.id to SecretValue("fixture-a"))),
            selectionStore = FakeSelectionStore(deleted.id)
        )

        assertEquals(StartupProfile.Ready(first.id), startup)
    }

    @Test
    fun theStartupOverloadFallsBackToOnboardingWhenNothingIsStored() {
        assertEquals(
            StartupProfile.None,
            resolveStartupProfile(
                metadataStore = FakeMetadataStore(),
                credentialStore = FakeCredentialStore(),
                selectionStore = FakeSelectionStore(null)
            )
        )
    }

    @Test
    fun theStartupOverloadFallsBackToUnavailableWhenAllProfilesAreIncomplete() {
        val profile = profile("a-profile")
        assertEquals(
            StartupProfile.Unavailable,
            resolveStartupProfile(
                metadataStore = FakeMetadataStore(listOf(profile)),
                credentialStore = FakeCredentialStore(),
                selectionStore = FakeSelectionStore(profile.id)
            )
        )
    }

    private fun profile(id: String) = ServerProfile.create(
        id = id,
        profileName = "Fixture",
        baseUrl = "https://example.invalid"
    )

    private class FakeSelectionStore(
        private val value: String?,
        private val failRead: Boolean = false
    ) : ActiveServerSelectionStore {
        override fun read(): String? {
            if (failRead) throw IllegalStateException("synthetic selection read failure")
            return value
        }

        override fun write(profileId: String) = Unit
        override fun clear() = Unit
    }

    private class FakeMetadataStore(
        private val profiles: List<ServerProfile> = emptyList(),
        private val failList: Boolean = false,
        private val failGet: Boolean = false
    ) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) = Unit
        override fun get(profileId: String): ServerProfile? {
            if (failGet) throw IllegalStateException("synthetic metadata read failure")
            return profiles.firstOrNull { it.id == profileId }
        }

        override fun list(): List<ServerProfile> {
            if (failList) throw IllegalStateException("synthetic metadata failure")
            return profiles
        }

        override fun delete(profileId: String) = Unit
    }

    private class FakeCredentialStore(
        private val values: Map<String, SecretValue> = emptyMap(),
        private val failGet: Boolean = false
    ) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String): SecretValue? {
            if (failGet) throw IllegalStateException("synthetic credential failure")
            return values[profileId]
        }

        override fun delete(profileId: String) = Unit
    }

    private class RecordingCredentialStore : CredentialStore {
        var getCalls = 0

        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String): SecretValue? {
            getCalls++
            return null
        }

        override fun delete(profileId: String) = Unit
    }
}
