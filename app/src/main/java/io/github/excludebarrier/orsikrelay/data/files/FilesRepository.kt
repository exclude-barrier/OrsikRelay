package io.github.excludebarrier.orsikrelay.data.files

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.FilesTransport
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.network.HermesFilesClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [FilesSource]. A "yes" from the capabilities is kept for the
 * process (a later switch-off answers `endpoint_out_of_scope`, mapped to
 * Unsupported); a "no" is asked again next time. A missing profile or
 * credential fails closed.
 */
class FilesRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val transport: FilesTransport = HermesFilesClient()
) : FilesSource {

    private val supported = ConcurrentHashMap<String, Boolean>()

    override fun list(profileId: String, path: String): FilesResult<FileListing> =
        call(profileId) { baseUrl, key -> transport.list(baseUrl, key, path) }

    override fun read(profileId: String, path: String): FilesResult<FileContent> =
        call(profileId) { baseUrl, key -> transport.read(baseUrl, key, path) }

    override fun projects(profileId: String): FilesResult<ProjectListing> =
        call(profileId) { baseUrl, key -> transport.projects(baseUrl, key) }

    private inline fun <T> call(profileId: String, read: (String, SecretValue) -> FilesResult<T>): FilesResult<T> {
        if (profileId.isBlank()) return FilesResult.Failed
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        } ?: return FilesResult.Failed
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) return FilesResult.Failed
        return try {
            val advertised = supported[profileId] == true ||
                transport.supports(profile.baseUrl, credential).also { if (it) supported[profileId] = true }
            if (!advertised) return FilesResult.Unsupported
            read(profile.baseUrl, credential).also { if (it == FilesResult.Unsupported) supported.remove(profileId) }
        } catch (_: Exception) {
            FilesResult.Failed
        }
    }
}
