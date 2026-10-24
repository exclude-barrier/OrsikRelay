package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectCommit
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the read-only files and projects client: the capability
 * check, the list/projects parsing with its drop rules, the content type
 * mapping, the 8 MiB bound and the closed error mapping. A TLS
 * [MockWebServer] plays the bridge because the client refuses plain HTTP.
 */
class HermesFilesClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesFilesClient
    private val key = SecretValue("fixture_key_123")

    private val endpointPaths = mapOf(
        "files_list" to "/v1/files/list",
        "files_content" to "/v1/files/content",
        "projects" to "/v1/projects"
    )

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesFilesClient(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val base get() = server.url("/p").toString().trimEnd('/')

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun bytes(body: ByteArray, contentType: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(Buffer().write(body))

    private fun capabilities(
        files: Boolean? = true,
        projects: Boolean? = true,
        method: String = "GET",
        paths: Map<String, String> = endpointPaths,
        omit: String? = null
    ): String {
        val features = JSONObject()
        files?.let { features.put("files", it) }
        projects?.let { features.put("projects", it) }
        val endpoints = JSONObject()
        for ((endpoint, path) in paths) {
            if (endpoint == omit) continue
            endpoints.put(endpoint, JSONObject().put("method", method).put("path", path))
        }
        return JSONObject().put("features", features).put("endpoints", endpoints).toString()
    }

    private fun listing(
        path: String = "",
        entries: List<JSONObject> = emptyList(),
        truncated: Boolean = false
    ): String = JSONObject()
        .put("object", "orsik.files.list")
        .put("path", path)
        .put("entries", JSONArray(entries))
        .put("truncated", truncated)
        .toString()

    private fun entry(name: String, type: String, size: Any? = JSONObject.NULL, modifiedAt: Any? = JSONObject.NULL) =
        JSONObject().put("name", name).put("type", type).put("size", size).put("modified_at", modifiedAt)

    private fun assertContent(expectedKind: FileContentKind, expected: ByteArray, result: FilesResult<FileContent>) {
        assertTrue("expected Ready, got $result", result is FilesResult.Ready)
        val content = (result as FilesResult.Ready<FileContent>).value
        assertEquals(expectedKind, content.kind)
        assertArrayEquals(expected, content.bytes)
    }

    // --- supports ---------------------------------------------------------

    @Test
    fun supportsNeedsBothFeaturesAndAllThreeExactEndpoints() {
        server.enqueue(json(capabilities()))

        assertTrue(client.supports(base, key))

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/p/v1/capabilities", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))

        server.enqueue(json(capabilities(files = false)))
        server.enqueue(json(capabilities(files = null)))
        server.enqueue(json(capabilities(projects = false)))
        server.enqueue(json(capabilities(projects = null)))
        server.enqueue(json(capabilities(omit = "files_list")))
        server.enqueue(json(capabilities(omit = "files_content")))
        server.enqueue(json(capabilities(omit = "projects")))
        server.enqueue(json(capabilities(method = "POST")))
        server.enqueue(json(capabilities(paths = endpointPaths + ("files_content" to "/v1/files/files/content"))))
        server.enqueue(json(capabilities(paths = endpointPaths + ("projects" to "/v1/project"))))

        repeat(10) { assertFalse("case $it", client.supports(base, key)) }
    }

    @Test
    fun supportsAcceptsAJsonContentTypeWithCharset() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(capabilities())
        )

        assertTrue(client.supports(base, key))
    }

    @Test
    fun supportsRejectsNonOkWrongTypeGarbageAndOversizedBodies() {
        server.enqueue(json(capabilities(), code = 403))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody(capabilities())
        )
        server.enqueue(json("""{"features":{"files":true,"projects":true},"endpoints":"""))
        server.enqueue(json("{}" + " ".repeat(HermesFilesClient.MAX_JSON_BYTES)))

        repeat(4) { assertFalse("case $it", client.supports(base, key)) }
    }

    @Test
    fun supportsRefusesAnUnsafeKeyOrPlainHttpWithoutARequest() {
        assertFalse(client.supports(base, SecretValue("bad key")))
        assertFalse(client.supports(base, SecretValue("bad\nkey")))
        assertFalse(client.supports(base, SecretValue("")))
        assertFalse(client.supports(base.replace("https://", "http://"), key))

        assertEquals(0, server.requestCount)
    }

    // --- list -------------------------------------------------------------

    @Test
    fun listSendsTheBearerKeyAndNoQueryAtTheRoot() {
        server.enqueue(json(listing()))

        assertEquals(
            FilesResult.Ready(FileListing("", emptyList(), false)),
            client.list(base, key, "")
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/p/v1/files/list", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
    }

    @Test
    fun listSendsThePathQueryOnlyWhenNonEmptyAndEncoded() {
        server.enqueue(json(listing()))
        assertEquals(FilesResult.Ready(FileListing("", emptyList(), false)), client.list(base, key, ""))
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.query)

        val path = "a b/ü&#'%+"
        server.enqueue(json(listing(path = path)))
        assertEquals(FilesResult.Ready(FileListing(path, emptyList(), false)), client.list(base, key, path))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/p/v1/files/list", request.requestUrl!!.encodedPath)
        assertEquals(path, request.requestUrl!!.queryParameter("path"))
        assertNull(request.requestUrl!!.fragment)
        val encoded = request.requestUrl!!.encodedQuery!!
        for (part in listOf("%20", "%2F", "%C3%BC", "%26", "%23", "%27", "%25", "%2B")) {
            assertTrue("expected $part in $encoded", encoded.contains(part))
        }
    }

    @Test
    fun listParsesEntriesAndDropsMalformedOnes() {
        val entries = listOf(
            entry("docs", "dir", modifiedAt = 100),
            entry("note.txt", "file", size = 42, modifiedAt = 0),
            entry("pipe", "other", size = 7, modifiedAt = 8),
            entry("negative", "file", size = -1, modifiedAt = -2),
            entry("float", "file", size = 1.5, modifiedAt = 2.5),
            entry("ümlaut name.txt", "file", size = 1, modifiedAt = 2),
            entry(".", "dir"),
            entry("..", "dir"),
            entry("a/b", "file"),
            entry("a\u0000b", "file"),
            entry("", "file"),
            entry("socket", "socket"),
            JSONObject().put("name", "typeless"),
            JSONObject().put("type", "file")
        )
        server.enqueue(json(listing(path = "sub", entries = entries, truncated = true)))

        assertEquals(
            FilesResult.Ready(
                FileListing(
                    path = "sub",
                    entries = listOf(
                        FileEntry("docs", FileKind.Directory, null, 100),
                        FileEntry("note.txt", FileKind.File, 42, 0),
                        FileEntry("pipe", FileKind.Other, 7, 8),
                        FileEntry("negative", FileKind.File, null, null),
                        FileEntry("float", FileKind.File, null, null),
                        FileEntry("ümlaut name.txt", FileKind.File, 1, 2)
                    ),
                    truncated = true
                )
            ),
            client.list(base, key, "sub")
        )
    }

    @Test
    fun listDropsANonObjectEntryButKeepsTheRest() {
        val entries = JSONArray()
            .put("not an entry")
            .put(JSONObject().put("name", "kept").put("type", "file"))
        server.enqueue(
            json(
                JSONObject().put("object", "orsik.files.list").put("path", "")
                    .put("entries", entries).put("truncated", false).toString()
            )
        )

        assertEquals(
            FilesResult.Ready(FileListing("", listOf(FileEntry("kept", FileKind.File, null, null)), false)),
            client.list(base, key, "")
        )
    }

    @Test
    fun listFailsOnAWrongObjectOrEnvelope() {
        server.enqueue(json("""{"object":"other","path":"","entries":[],"truncated":false}"""))
        server.enqueue(json("""{"object":"orsik.files.list","path":"","truncated":false}"""))
        server.enqueue(json("""{"object":"orsik.files.list","entries":[],"truncated":false}"""))
        server.enqueue(json("""{"object":"orsik.files.list","path":"","entries":"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("""{"object":"orsik.files.list","path":"","entries":[]}""")
        )

        repeat(5) { assertTrue("case $it", client.list(base, key, "") == FilesResult.Failed) }
    }

    @Test
    fun listRefusesAPathItCannotSendWithoutARequest() {
        assertEquals(FilesResult.Failed, client.list(base, key, "a\u0000b"))
        assertEquals(FilesResult.Failed, client.list(base, key, "x".repeat(HermesFilesClient.MAX_PATH + 1)))

        assertEquals(0, server.requestCount)
    }

    // --- read -------------------------------------------------------------

    @Test
    fun readMapsTheClosedContentTypeSet() {
        val data = byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte())
        val cases = listOf(
            "text/plain; charset=utf-8" to FileContentKind.Text,
            "text/plain" to FileContentKind.Text,
            "image/png" to FileContentKind.Image,
            "image/jpeg" to FileContentKind.Image,
            "image/webp" to FileContentKind.Image,
            "image/gif" to FileContentKind.Image,
            "application/octet-stream" to FileContentKind.Binary
        )
        for ((contentType, kind) in cases) {
            server.enqueue(bytes(data, contentType))
            assertContent(kind, data, client.read(base, key, "file.bin"))
        }

        server.enqueue(bytes(data, "text/html; charset=utf-8"))
        assertEquals(FilesResult.Failed, client.read(base, key, "page.html"))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(data))
        )
        assertEquals(FilesResult.Failed, client.read(base, key, "typeless"))
    }

    @Test
    fun readSendsThePathQueryAndAcceptsExactlyEightMebibytes() {
        val data = ByteArray(HermesFilesClient.MAX_FILE_BYTES) { (it % 251).toByte() }
        server.enqueue(bytes(data, "application/octet-stream"))

        assertContent(FileContentKind.Binary, data, client.read(base, key, "dir/big.bin"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/p/v1/files/content", request.requestUrl!!.encodedPath)
        assertEquals("dir/big.bin", request.requestUrl!!.queryParameter("path"))
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
    }

    @Test
    fun readRejectsOneByteOverTheEightMebibyteBound() {
        val data = ByteArray(HermesFilesClient.MAX_FILE_BYTES + 1) { 7 }
        server.enqueue(bytes(data, "application/octet-stream"))

        assertEquals(FilesResult.TooLarge, client.read(base, key, "big.bin"))
    }

    @Test
    fun readMapsTheClosedErrors() {
        server.enqueue(json("""{"error":{"code":"path_not_allowed"}}""", 403))
        server.enqueue(json("""{"error":{"code":"endpoint_out_of_scope"}}""", 403))
        server.enqueue(json("""{"error":{"code":"query_not_allowed"}}""", 403))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/plain").setBody("no"))
        server.enqueue(json("""{"error":{"code":"not_found"}}""", 404))
        server.enqueue(json("""{"error":{"code":"file_too_large"}}""", 413))
        server.enqueue(json("""{"error":{"code":"boom"}}""", 500))

        assertEquals(FilesResult.Refused, client.read(base, key, "../x"))
        assertEquals(FilesResult.Unsupported, client.read(base, key, "a"))
        assertEquals(FilesResult.Failed, client.read(base, key, "a"))
        assertEquals(FilesResult.Failed, client.read(base, key, "a"))
        assertEquals(FilesResult.NotFound, client.read(base, key, "a"))
        assertEquals(FilesResult.TooLarge, client.read(base, key, "a"))
        assertEquals(FilesResult.Failed, client.read(base, key, "a"))
    }

    @Test
    fun readDoesNotFollowARedirect() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/p/v1/files/content?path=target").toString())
        )

        assertEquals(FilesResult.Failed, client.read(base, key, "a"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun readsFailClosedWithoutARequestForUnsafeInput() {
        assertEquals(FilesResult.Failed, client.list(base, SecretValue("bad key"), "a"))
        assertEquals(FilesResult.Failed, client.read(base, SecretValue("bad key"), "a"))
        assertEquals(FilesResult.Failed, client.projects(base, SecretValue("bad key")))
        val http = base.replace("https://", "http://")
        assertEquals(FilesResult.Failed, client.list(http, key, "a"))
        assertEquals(FilesResult.Failed, client.read(http, key, "a"))
        assertEquals(FilesResult.Failed, client.projects(http, key))

        assertEquals(0, server.requestCount)
    }

    // --- projects ---------------------------------------------------------

    @Test
    fun projectsParsesFieldsAndDropsMalformedEntries() {
        val projects = JSONArray()
            .put(
                JSONObject().put("name", "repo").put("path", "group/repo").put("branch", "main")
                    .put("dirty", true)
                    .put(
                        "last_commit",
                        JSONObject().put("short", "abc1234").put("committed_at", 1_700_000_000)
                            .put("subject", "fix\u0001it\nnow")
                    )
            )
            .put(
                JSONObject().put("name", "clean").put("path", "clean").put("dirty", JSONObject.NULL)
                    .put("last_commit", JSONObject.NULL)
            )
            .put(
                JSONObject().put("name", "no-branch").put("path", "no-branch").put("branch", JSONObject.NULL)
                    .put(
                        "last_commit",
                        JSONObject().put("short", "def5678").put("committed_at", 0).put("subject", "initial")
                    )
            )
            .put(
                JSONObject().put("name", "bad-time").put("path", "bad-time")
                    .put(
                        "last_commit",
                        JSONObject().put("short", "abc").put("committed_at", -5).put("subject", "x")
                    )
            )
            .put(
                JSONObject().put("name", "no-time").put("path", "no-time")
                    .put("last_commit", JSONObject().put("short", "abc").put("subject", "x"))
            )
            .put(
                JSONObject().put("name", "no-subject").put("path", "no-subject")
                    .put("last_commit", JSONObject().put("short", "abc").put("committed_at", 5))
            )
            .put(
                JSONObject().put("name", "bad-short").put("path", "bad-short")
                    .put(
                        "last_commit",
                        JSONObject().put("short", "a\nb").put("committed_at", 5).put("subject", "x")
                    )
            )
            .put(
                JSONObject().put("name", "bad-branch").put("path", "bad-branch").put("branch", "ma\nin")
                    .put("dirty", JSONObject.NULL)
                    .put("last_commit", JSONObject.NULL)
            )
            .put(JSONObject().put("name", "bad/name").put("path", "bad-name"))
            .put(JSONObject().put("name", "bad-path").put("path", "x".repeat(HermesFilesClient.MAX_PATH + 1)))

        server.enqueue(
            json(
                JSONObject().put("object", "orsik.projects.list")
                    .put("projects", projects)
                    .put("truncated", true)
                    .toString()
            )
        )

        assertEquals(
            FilesResult.Ready(
                ProjectListing(
                    projects = listOf(
                        Project(
                            name = "repo",
                            path = "group/repo",
                            branch = "main",
                            dirty = true,
                            lastCommit = ProjectCommit("abc1234", 1_700_000_000, "fix?it?now")
                        ),
                        Project("clean", "clean", null, null, null),
                        Project("no-branch", "no-branch", null, null, ProjectCommit("def5678", 0, "initial")),
                        Project("bad-time", "bad-time", null, null, null),
                        Project("no-time", "no-time", null, null, null),
                        Project("no-subject", "no-subject", null, null, null),
                        Project("bad-short", "bad-short", null, null, null),
                        Project("bad-branch", "bad-branch", null, null, null)
                    ),
                    truncated = true
                )
            ),
            client.projects(base, key)
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/p/v1/projects", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
    }

    @Test
    fun projectsTruncationDefaultsToFalseAndWrongShapesFail() {
        server.enqueue(json("""{"object":"orsik.projects.list","projects":[]}"""))
        assertEquals(FilesResult.Ready(ProjectListing(emptyList(), false)), client.projects(base, key))

        server.enqueue(json("""{"object":"other","projects":[]}"""))
        server.enqueue(json("""{"object":"orsik.projects.list"}"""))
        server.enqueue(json("""{"object":"orsik.projects.list","projects":{}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain")
                .setBody("""{"object":"orsik.projects.list","projects":[]}""")
        )

        repeat(4) { assertEquals(FilesResult.Failed, client.projects(base, key)) }
    }
}
