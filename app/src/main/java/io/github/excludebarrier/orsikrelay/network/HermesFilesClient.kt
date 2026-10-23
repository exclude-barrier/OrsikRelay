package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesTransport
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectCommit
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * Reads the bridge's read-only files and projects. HTTPS only, no
 * redirects, bounded bodies; a malformed entry is dropped, a malformed
 * envelope fails the whole read.
 */
class HermesFilesClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient(readTimeoutSeconds = 20, callTimeoutSeconds = 30)
) : FilesTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
        val url = url(baseUrl, apiKey, "v1", "capabilities") ?: return false
        return try {
            client.newCall(get(url, apiKey)).execute().use { response ->
                if (response.code != 200) return false
                requireJsonContentType(response) { return false }
                val root = JSONObject(readBoundedUtf8(response.body, MAX_JSON_BYTES) { return false })
                val endpoints = root.optJSONObject("endpoints") ?: return false
                val features = root.optJSONObject("features") ?: return false
                features.opt("files") == true && features.opt("projects") == true &&
                    ENDPOINTS.all { (key, path) ->
                        val endpoint = endpoints.optJSONObject(key)
                        endpoint?.opt("method") == "GET" && endpoint.opt("path") == path
                    }
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun list(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileListing> {
        val url = url(baseUrl, apiKey, "v1", "files", "list")?.withPath(path) ?: return FilesResult.Failed
        return json(url, apiKey) { root ->
            if (root.opt("object") != "orsik.files.list") return@json null
            val listedPath = root.opt("path") as? String ?: return@json null
            val array = root.optJSONArray("entries") ?: return@json null
            val entries = (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = (item.opt("name") as? String)?.takeIf { isSafeName(it) } ?: return@mapNotNull null
                val kind = when (item.opt("type")) {
                    "dir" -> FileKind.Directory
                    "file" -> FileKind.File
                    "other" -> FileKind.Other
                    else -> return@mapNotNull null
                }
                FileEntry(name, kind, item.longOrNull("size"), item.longOrNull("modified_at"))
            }
            FileListing(listedPath, entries, root.opt("truncated") == true)
        }
    }

    override fun read(baseUrl: String, apiKey: SecretValue, path: String): FilesResult<FileContent> {
        val url = url(baseUrl, apiKey, "v1", "files", "content")?.withPath(path) ?: return FilesResult.Failed
        return try {
            client.newCall(get(url, apiKey)).execute().use { response ->
                error(response)?.let { return it }
                val kind = when (response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()) {
                    "text/plain" -> FileContentKind.Text
                    "image/png", "image/jpeg", "image/webp", "image/gif" -> FileContentKind.Image
                    "application/octet-stream" -> FileContentKind.Binary
                    else -> return FilesResult.Failed
                }
                val bytes = readBoundedBytes(response, MAX_FILE_BYTES) ?: return FilesResult.TooLarge
                FilesResult.Ready(FileContent(bytes, kind))
            }
        } catch (_: Exception) {
            FilesResult.Failed
        }
    }

    override fun projects(baseUrl: String, apiKey: SecretValue): FilesResult<ProjectListing> {
        val url = url(baseUrl, apiKey, "v1", "projects") ?: return FilesResult.Failed
        return json(url, apiKey) { root ->
            if (root.opt("object") != "orsik.projects.list") return@json null
            val array = root.optJSONArray("projects") ?: return@json null
            val projects = (0 until minOf(array.length(), MAX_PROJECTS)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = (item.opt("name") as? String)?.takeIf { isSafeName(it) } ?: return@mapNotNull null
                val path = (item.opt("path") as? String)?.takeIf { isSafeServerString(it, MAX_PATH) } ?: return@mapNotNull null
                val commit = item.optJSONObject("last_commit")?.let { c ->
                    val short = (c.opt("short") as? String)?.takeIf { isSafeServerString(it, 64) }
                    val at = c.longOrNull("committed_at")
                    val subject = (c.opt("subject") as? String)?.let(::printable)
                    if (short != null && at != null && subject != null) ProjectCommit(short, at, subject) else null
                }
                Project(
                    name = name,
                    path = path,
                    branch = (item.opt("branch") as? String)?.takeIf { isSafeServerString(it, MAX_NAME) },
                    dirty = item.opt("dirty") as? Boolean,
                    lastCommit = commit
                )
            }
            ProjectListing(projects, root.opt("truncated") == true)
        }
    }

    private inline fun <T> json(url: HttpUrl, apiKey: SecretValue, parse: (JSONObject) -> T?): FilesResult<T> = try {
        client.newCall(get(url, apiKey)).execute().use { response ->
            error(response)?.let { return it }
            requireJsonContentType(response) { return FilesResult.Failed }
            val root = JSONObject(readBoundedUtf8(response.body, MAX_JSON_BYTES) { return FilesResult.Failed })
            parse(root)?.let { FilesResult.Ready(it) } ?: FilesResult.Failed
        }
    } catch (_: Exception) {
        FilesResult.Failed
    }

    /** The closed error mapping; `null` for a 200. */
    private fun error(response: Response): FilesResult<Nothing>? = when (response.code) {
        200 -> null
        403 -> when (closedErrorCode(response)) {
            "path_not_allowed" -> FilesResult.Refused
            "endpoint_out_of_scope" -> FilesResult.Unsupported
            else -> FilesResult.Failed
        }
        404 -> FilesResult.NotFound
        413 -> FilesResult.TooLarge
        else -> FilesResult.Failed
    }

    private fun closedErrorCode(response: Response): String? = try {
        JSONObject(readBoundedUtf8(response.body, 4096) { return null })
            .optJSONObject("error")?.opt("code") as? String
    } catch (_: Exception) {
        null
    }

    private fun readBoundedBytes(response: Response, max: Int): ByteArray? {
        val source = response.body.source()
        // One byte more than allowed tells "too large" from "exactly max".
        if (source.request(max + 1L)) return null
        return source.buffer.readByteArray()
    }

    private fun url(baseUrl: String, apiKey: SecretValue, vararg segments: String): HttpUrl? {
        if (!isSafeApiKey(apiKey)) return null
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl() ?: return null
        return base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
    }

    private fun HttpUrl.withPath(path: String): HttpUrl? {
        if (path.length > MAX_PATH || path.any { it == '\u0000' }) return null
        return if (path.isEmpty()) this else newBuilder().addQueryParameter("path", path).build()
    }

    private fun get(url: HttpUrl, apiKey: SecretValue): Request =
        apiKey.useRaw { rawKey -> Request.Builder().url(url).get().header("Authorization", "Bearer $rawKey").build() }

    private fun JSONObject.longOrNull(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }

    /** A file name may hold any character but "/" and NUL; control characters are shown as "?". */
    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME && '/' !in name && '\u0000' !in name && name != "." && name != ".."

    private fun printable(text: String): String =
        text.take(MAX_NAME * 2).map { if (it.isISOControl()) '?' else it }.joinToString("")

    internal companion object {
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_ENTRIES = 2000
        const val MAX_PROJECTS = 50
        const val MAX_NAME = 255
        const val MAX_PATH = 4096
        val ENDPOINTS = listOf(
            "files_list" to "/v1/files/list",
            "files_content" to "/v1/files/content",
            "projects" to "/v1/projects"
        )
    }
}
