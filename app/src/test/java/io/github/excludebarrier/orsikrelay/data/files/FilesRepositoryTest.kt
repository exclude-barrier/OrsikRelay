package io.github.excludebarrier.orsikrelay.data.files

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesTransport
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the production [FilesSource]: fail closed without profile,
 * credential or a usable id, the capability gate and its per-profile cache,
 * and the "a read said Unsupported" cache drop.
 */
class FilesRepositoryTest {

    private class Transport(var supported: Boolean = true) : FilesTransport {
        var supportCalls = 0
        var listCalls = 0
        var readCalls = 0
        var projectsCalls = 0
        var lastBaseUrl: String? = null
        var lastKey: SecretValue? = null
        var listResult: FilesResult<FileListing> =
            FilesResult.Ready(FileListing("", emptyList(), false))
        var readResult: FilesResult<FileContent> =
            FilesResult.Ready(FileContent(byteArrayOf(1, 2, 3), FileContentKind.Binary))
        var projectsResult: FilesResult<ProjectListing> =
            FilesResult.Ready(ProjectListing(emptyList(), false))

        override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
            supportCalls++
            return supported
        }

        override fun list(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileListing> {
            listCalls++
            lastBaseUrl = baseUrl
            lastKey = apiKey
            return listResult
        }

        override fun read(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileContent> {
            readCalls++
            lastBaseUrl = baseUrl
            lastKey = apiKey
            return readResult
        }

        override fun projects(baseUrl: String, apiKey: SecretValue): FilesResult<ProjectListing> {
            projectsCalls++
            lastBaseUrl = baseUrl
            lastKey = apiKey
            return projectsResult
        }
    }

    private class Metadata(val profiles: List<ServerProfile>) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) = Unit
        override fun get(profileId: String) = profiles.firstOrNull { it.id == profileId }
        override fun list() = profiles
        override fun delete(profileId: String) = Unit
    }

    private class Credentials(val values: Map<String, SecretValue>) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String) = values[profileId]
        override fun delete(profileId: String) = Unit
    }

    private fun repository(
        transport: Transport,
        withProfile: Boolean = true,
        withKey: Boolean = true,
        key: SecretValue = SecretValue("k")
    ): FilesRepository {
        val profile = ServerProfile.create("p", "P", "https://example.invalid")
        val credentials = Credentials(if (withKey) mapOf("p" to key) else emptyMap())
        return FilesRepository(
            ServerProfileRepository(
                Metadata(if (withProfile) listOf(profile) else emptyList()),
                credentials,
                idFactory = { "x" }
            ),
            credentials,
            transport
        )
    }

    @Test
    fun aBlankIdFailsWithoutTouchingTheTransport() {
        val transport = Transport()

        assertEquals(FilesResult.Failed, repository(transport).list("", "a"))
        assertEquals(FilesResult.Failed, repository(transport).list("  ", "a"))
        assertEquals(FilesResult.Failed, repository(transport).read("", "a"))
        assertEquals(FilesResult.Failed, repository(transport).projects(""))

        assertEquals(0, transport.supportCalls + transport.listCalls + transport.readCalls + transport.projectsCalls)
    }

    @Test
    fun aMissingProfileOrCredentialFailsWithoutTouchingTheTransport() {
        val noProfile = Transport()
        val noKey = Transport()
        val emptyKey = Transport()

        assertEquals(FilesResult.Failed, repository(noProfile, withProfile = false).list("p", "a"))
        assertEquals(FilesResult.Failed, repository(noKey, withKey = false).list("p", "a"))
        assertEquals(FilesResult.Failed, repository(emptyKey, key = SecretValue("")).list("p", "a"))

        for (transport in listOf(noProfile, noKey, emptyKey)) {
            assertEquals(0, transport.supportCalls + transport.listCalls + transport.readCalls + transport.projectsCalls)
        }
    }

    @Test
    fun withoutTheCapabilityEveryCallIsUnsupportedAndOnlySupportsRuns() {
        val transport = Transport(supported = false)
        val repository = repository(transport)

        assertEquals(FilesResult.Unsupported, repository.list("p", "a"))
        assertEquals(FilesResult.Unsupported, repository.read("p", "a"))
        assertEquals(FilesResult.Unsupported, repository.projects("p"))

        assertEquals(3, transport.supportCalls)
        assertEquals(0, transport.listCalls + transport.readCalls + transport.projectsCalls)
    }

    @Test
    fun aYesIsCachedSoOnlyTheFirstCallAsks() {
        val transport = Transport(supported = true)
        val repository = repository(transport)
        val expected = FilesResult.Ready(FileListing("", emptyList(), false))

        repeat(3) { assertEquals(expected, repository.list("p", "a")) }
        repository.read("p", "a")
        repository.projects("p")

        assertEquals(1, transport.supportCalls)
        assertEquals(3, transport.listCalls)
        assertEquals(1, transport.readCalls)
        assertEquals(1, transport.projectsCalls)
        assertEquals("https://example.invalid", transport.lastBaseUrl)
        assertTrue(transport.lastKey != null && !transport.lastKey!!.isEmpty())
    }

    @Test
    fun aNoIsAskedAgainSoABridgeUpdateShowsUp() {
        val transport = Transport(supported = false)
        val repository = repository(transport)

        assertEquals(FilesResult.Unsupported, repository.list("p", "a"))
        transport.supported = true
        assertEquals(FilesResult.Ready(FileListing("", emptyList(), false)), repository.list("p", "a"))

        assertEquals(2, transport.supportCalls)
        assertEquals(1, transport.listCalls)
    }

    @Test
    fun aReadAnsweringUnsupportedDropsTheCachedYes() {
        val transport = Transport(supported = true)
        transport.readResult = FilesResult.Unsupported
        val repository = repository(transport)

        assertEquals(FilesResult.Unsupported, repository.read("p", "a"))
        assertEquals(1, transport.supportCalls)

        // The next call must ask the capabilities again instead of trusting the
        // earlier yes; the bridge answering yes now serves the read.
        transport.readResult = FilesResult.Ready(FileContent(byteArrayOf(9), FileContentKind.Text))
        val result = repository.read("p", "a")
        assertTrue(result is FilesResult.Ready)
        val content = (result as FilesResult.Ready<FileContent>).value
        assertArrayEquals(byteArrayOf(9), content.bytes)
        assertEquals(FileContentKind.Text, content.kind)
        assertEquals(2, transport.supportCalls)
        assertEquals(2, transport.readCalls)
    }

    @Test
    fun anUnsupportedProjectsAnswerAlsoDropsTheCachedYes() {
        val transport = Transport(supported = true)
        transport.projectsResult = FilesResult.Unsupported
        val repository = repository(transport)

        assertEquals(FilesResult.Unsupported, repository.projects("p"))
        // Without the drop the cached yes would serve the list without asking.
        transport.supported = false
        assertEquals(FilesResult.Unsupported, repository.list("p", "a"))

        assertEquals(2, transport.supportCalls)
    }

    @Test
    fun aReadyListingIsReturnedWithItsEntries() {
        val transport = Transport()
        transport.listResult = FilesResult.Ready(
            FileListing("sub", listOf(FileEntry("a.txt", FileKind.File, 3, 4)), false)
        )
        val repository = repository(transport)

        assertEquals(
            FilesResult.Ready(
                FileListing("sub", listOf(FileEntry("a.txt", FileKind.File, 3, 4)), false)
            ),
            repository.list("p", "sub")
        )
    }
}
