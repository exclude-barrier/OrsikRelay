package io.github.excludebarrier.orsikrelay.domain.files

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * The bridge's read-only file browser and project list below the operator's
 * `BRIDGE_FILES_ROOT` (`/v1/files/list`, `/v1/files/content`, `/v1/projects`).
 * Paths are relative to that root, "" is the root itself.
 */
enum class FileKind { Directory, File, Other }

data class FileEntry(val name: String, val kind: FileKind, val sizeBytes: Long?, val modifiedAtSeconds: Long?)

data class FileListing(val path: String, val entries: List<FileEntry>, val truncated: Boolean)

/** Content types the bridge sends: an image, UTF-8 text or anything else. */
enum class FileContentKind { Text, Image, Binary }

class FileContent(val bytes: ByteArray, val kind: FileContentKind)

data class ProjectCommit(val short: String, val committedAtSeconds: Long, val subject: String)

data class Project(
    val name: String,
    val path: String,
    val branch: String?,
    /** `null` when git could not tell. */
    val dirty: Boolean?,
    val lastCommit: ProjectCommit?
)

data class ProjectListing(val projects: List<Project>, val truncated: Boolean)

sealed interface FilesResult<out T> {
    data class Ready<T>(val value: T) : FilesResult<T>

    /** The server does not offer files (switched off or an older bridge). */
    data object Unsupported : FilesResult<Nothing>
    data object NotFound : FilesResult<Nothing>

    /** The path leaves the root (e.g. a link pointing outside). */
    data object Refused : FilesResult<Nothing>

    /** Larger than the bridge sends (8 MiB). */
    data object TooLarge : FilesResult<Nothing>

    /** Network, TLS, auth or an unexpected answer. */
    data object Failed : FilesResult<Nothing>
}

/** UI seam; every call blocks (run it on `Dispatchers.IO`), idempotent reads only. */
interface FilesSource {
    fun list(profileId: String, path: String): FilesResult<FileListing>
    fun read(profileId: String, path: String): FilesResult<FileContent>
    fun projects(profileId: String): FilesResult<ProjectListing>
}

/** Authenticated transport; production: `HermesFilesClient`. */
interface FilesTransport {
    fun supports(baseUrl: String, apiKey: SecretValue): Boolean
    fun list(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileListing>
    fun read(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileContent>
    fun projects(baseUrl: String, apiKey: SecretValue): FilesResult<ProjectListing>
}

/** The child path of [name] in [parent] ("" is the root). */
fun childPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

/** The parent of [path], or `null` at the root. */
fun parentPath(path: String): String? = when {
    path.isEmpty() -> null
    '/' !in path -> ""
    else -> path.substringBeforeLast('/')
}
