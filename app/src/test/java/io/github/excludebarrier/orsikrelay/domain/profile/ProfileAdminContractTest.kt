package io.github.excludebarrier.orsikrelay.domain.profile

import io.github.excludebarrier.orsikrelay.data.profile.ProfileDraftClearer
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Contract test: [ServerProfileAdmin] must delegate 1:1 to the
 * existing verified [ServerProfileRepository] list/delete path, including the
 * draft cleaner seam, and must not introduce a parallel deletion
 * implementation.
 */
@RunWith(Parameterized::class)
class ProfileAdminContractTest {

    @Parameterized.Parameter
    @field:JvmField
    var withClearer: Boolean = false

    private val events = mutableListOf<String>()
    private val profiles = linkedMapOf<String, ServerProfile>()
    private val credentials = linkedMapOf<String, SecretValue>()
    private lateinit var admin: ServerProfileAdmin

    // Built AFTER parameter injection (JUnit injects [withClearer] after
    // construction), so the repository always sees the intended clearer.
    @org.junit.Before
    fun setUp() {
        admin = ServerProfileAdmin(
            ServerProfileRepository(
                metadataStore = object : ServerProfileMetadataStore {
                    override fun save(profile: ServerProfile) {
                        events += "metadata.save"
                        profiles[profile.id] = profile
                    }

                    override fun get(profileId: String): ServerProfile? = profiles[profileId]

                    override fun list(): List<ServerProfile> = profiles.values.toList()

                    override fun delete(profileId: String) {
                        events += "metadata.delete"
                        profiles.remove(profileId)
                    }
                },
                credentialStore = object : CredentialStore {
                    override fun put(profileId: String, secret: SecretValue) {
                        events += "credential.put"
                        credentials[profileId] = secret
                    }

                    override fun get(profileId: String): SecretValue? = credentials[profileId]

                    override fun delete(profileId: String) {
                        events += "credential.delete"
                        credentials.remove(profileId)
                    }
                },
                profileDraftClearer = if (withClearer) {
                    ProfileDraftClearer { events += "drafts.cleared" }
                } else null
            )
        )
    }

    private fun seed(id: String, name: String) {
        profiles[id] = ServerProfile.create(id, name, BASE_URL)
        credentials[id] = SecretValue("synthetic")
    }

    @Test
    fun listReturnsExactlyTheStoredProfiles() {
        seed("profile-a", "Alpha")
        seed("profile-b", "Beta")

        val result = admin.listProfiles()

        assertEquals(listOf("profile-a", "profile-b"), result.map { it.id })
        assertTrue("list must not mutate", events.isEmpty())
    }

    @Test
    fun deleteDelegatesToTheVerifiedRepositoryPath() {
        seed("profile-a", "Alpha")
        events.clear()

        admin.deleteProfile("profile-a")

        assertEquals(
            listOf(
                "credential.delete",
                "metadata.delete"
            ) + if (withClearer) listOf("drafts.cleared") else emptyList(),
            events
        )
        assertTrue(profiles.isEmpty())
        assertTrue(credentials.isEmpty())
    }

    private companion object {
        const val BASE_URL = "https://synthetic.local"

        @Parameterized.Parameters
        @JvmStatic
        fun parameters(): Collection<*> = listOf(false, true)
    }
}
