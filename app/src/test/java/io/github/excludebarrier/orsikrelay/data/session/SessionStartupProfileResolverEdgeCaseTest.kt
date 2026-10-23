package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.profile.ServerSelection
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Edge cases of the startup resolution when the persisted active selection is
 * stale, blank, unreadable, credentialless or points at a deleted profile.
 *
 * Every such selection must fall through deterministically to the existing
 * sorted-by-id resolution, and a selected-but-missing profile must never be
 * used or have its credential read.
 */
class SessionStartupProfileResolverEdgeCaseTest {

    @Test
    fun aSelectedProfileMissingFromMetadataFallsBackToTheFirstCompleteProfile() {
        val a = profile("a-profile")
        val b = profile("b-profile")
        val metadata = FakeMetadataStore(listOf(b, a))
        val credentials = FakeCredentialStore(
            mapOf(a.id to SecretValue("fixture-a"), b.id to SecretValue("fixture-b"))
        )

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore("profile-deleted")
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
        assertTrue("the selected id is checked against metadata", "profile-deleted" in metadata.getIds)
        assertFalse(
            "a missing selected profile never reaches the credential store",
            "profile-deleted" in credentials.getIds
        )
    }

    @Test
    fun aSelectedProfileWithoutCredentialFallsBackToTheNextCompleteProfile() {
        val a = profile("a-profile")
        val z = profile("z-profile")
        val metadata = FakeMetadataStore(listOf(z, a))
        val credentials = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore(z.id)
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
        assertEquals(listOf(z.id, a.id), credentials.getIds)
    }

    @Test
    fun aSelectedProfileWhoseCredentialReadThrowsFallsBackToAnotherCompleteProfile() {
        val a = profile("a-profile")
        val z = profile("z-profile")
        val metadata = FakeMetadataStore(listOf(z, a))
        val credentials = FakeCredentialStore(
            values = mapOf(a.id to SecretValue("fixture-a"), z.id to SecretValue("fixture-z")),
            failGetFor = setOf(z.id)
        )

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore(z.id)
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
    }

    @Test
    fun aSelectedProfileWhoseMetadataReadThrowsFallsBackToAnotherCompleteProfile() {
        val a = profile("a-profile")
        val z = profile("z-profile")
        val metadata = FakeMetadataStore(
            profiles = listOf(z, a),
            failGetFor = setOf(z.id)
        )
        val credentials = FakeCredentialStore(
            mapOf(a.id to SecretValue("fixture-a"), z.id to SecretValue("fixture-z"))
        )

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore(z.id)
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
    }

    @Test
    fun aSelectionReadFailureFallsBackToTheFirstCompleteProfile() {
        val a = profile("a-profile")
        val metadata = FakeMetadataStore(listOf(a))
        val credentials = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore("ignored", failRead = true)
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
    }

    @Test
    fun aBlankStoredSelectionNeverReadsMetadataOrCredentialForIt() {
        val a = profile("a-profile")
        val metadata = FakeMetadataStore(listOf(a))
        val credentials = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore("   ")
        )

        assertEquals(StartupProfile.Ready(a.id), startup)
        assertTrue("a blank selection is not looked up", metadata.getIds.isEmpty())
        assertEquals(listOf(a.id), credentials.getIds)
    }

    @Test
    fun aWhitespacePaddedStoredSelectionDoesNotMatchTheTrimmedProfileId() {
        val a = profile("a-profile")
        val metadata = FakeMetadataStore(listOf(a))
        val credentials = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore(" ${a.id} ")
        )

        // Fail closed: IDs are opaque, so the padded value is not silently
        // trimmed into a match; the normal resolution still finds the profile.
        assertEquals(StartupProfile.Ready(a.id), startup)
        assertTrue(" ${a.id} " in metadata.getIds)
    }

    @Test
    fun aSelectedMissingProfileWithNoStoredProfilesIsOnboarding() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore("profile-deleted")
        )

        assertEquals(StartupProfile.None, startup)
        assertTrue(credentials.getIds.isEmpty())
    }

    @Test
    fun anInvalidSelectionWithAFailingMetadataListIsUnavailable() {
        val startup = resolveStartupProfile(
            metadataStore = FakeMetadataStore(failList = true),
            credentialStore = FakeCredentialStore(),
            selectionStore = FakeSelectionStore("profile-deleted")
        )

        assertEquals(StartupProfile.Unavailable, startup)
    }

    @Test
    fun aValidSelectionWinsWithoutConsultingTheMetadataList() {
        val a = profile("a-profile")
        val z = profile("z-profile")
        val metadata = FakeMetadataStore(profiles = listOf(z, a), failList = true)
        val credentials = FakeCredentialStore(
            mapOf(a.id to SecretValue("fixture-a"), z.id to SecretValue("fixture-z"))
        )

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = credentials,
            selectionStore = FakeSelectionStore(z.id)
        )

        assertEquals(StartupProfile.Ready(z.id), startup)
        assertEquals("the list is never consulted for a valid selection", 0, metadata.listCalls)
    }

    @Test
    fun aCredentiallessSelectionWithOnlyIncompleteProfilesIsUnavailable() {
        val a = profile("a-profile")
        val b = profile("b-profile")
        val metadata = FakeMetadataStore(listOf(a, b))

        val startup = resolveStartupProfile(
            metadataStore = metadata,
            credentialStore = FakeCredentialStore(),
            selectionStore = FakeSelectionStore(a.id)
        )

        assertEquals(StartupProfile.Unavailable, startup)
    }

    @Test
    fun resolveActiveServerSelectionRejectsAWhitespacePaddedStoredId() {
        val a = profile("a-profile")
        val selection = resolveActiveServerSelection(
            selectionStore = FakeSelectionStore(" ${a.id} "),
            metadataStore = FakeMetadataStore(listOf(a)),
            credentialStore = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))
        )

        assertEquals(ServerSelection.Invalid, selection)
    }

    @Test
    fun resolveActiveServerSelectionRejectsADeletedProfileWithoutReadingItsCredential() {
        val a = profile("a-profile")
        val credentials = FakeCredentialStore(mapOf(a.id to SecretValue("fixture-a")))
        val selection = resolveActiveServerSelection(
            selectionStore = FakeSelectionStore("profile-deleted"),
            metadataStore = FakeMetadataStore(listOf(a)),
            credentialStore = credentials
        )

        assertEquals(ServerSelection.Invalid, selection)
        assertTrue(credentials.getIds.isEmpty())
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
        private val failGetFor: Set<String> = emptySet()
    ) : ServerProfileMetadataStore {
        val getIds = CopyOnWriteArrayList<String>()
        var listCalls = 0

        override fun save(profile: ServerProfile) = Unit

        override fun get(profileId: String): ServerProfile? {
            getIds += profileId
            if (profileId in failGetFor) {
                throw IllegalStateException("synthetic metadata get failure")
            }
            return profiles.firstOrNull { it.id == profileId }
        }

        override fun list(): List<ServerProfile> {
            listCalls++
            if (failList) throw IllegalStateException("synthetic metadata list failure")
            return profiles
        }

        override fun delete(profileId: String) = Unit
    }

    private class FakeCredentialStore(
        private val values: Map<String, SecretValue> = emptyMap(),
        private val failGetFor: Set<String> = emptySet()
    ) : CredentialStore {
        val getIds = CopyOnWriteArrayList<String>()

        override fun put(profileId: String, secret: SecretValue) = Unit

        override fun get(profileId: String): SecretValue? {
            getIds += profileId
            if (profileId in failGetFor) {
                throw IllegalStateException("synthetic credential get failure")
            }
            return values[profileId]
        }

        override fun delete(profileId: String) = Unit
    }
}
