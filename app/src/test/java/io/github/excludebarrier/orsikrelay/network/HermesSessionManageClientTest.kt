package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesSessionManageClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesSessionManageClient
    private val key = SecretValue("fixture_key_123")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesSessionManageClient(
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

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun capabilities(archive: Any = true, delete: Any = true, patchMethod: String = "PATCH") = JSONObject()
        .put("features", JSONObject().put("session_archive", archive).put("session_delete", delete))
        .put(
            "endpoints",
            JSONObject()
                .put("session_update", JSONObject().put("method", patchMethod).put("path", "/api/sessions/{session_id}"))
                .put("session_delete", JSONObject().put("method", "DELETE").put("path", "/api/sessions/{session_id}"))
        ).toString()

    @Test
    fun capabilitiesWithBothFeaturesAndEndpointsEnableManagement() {
        server.enqueue(json(200, capabilities()))

        assertTrue(client.supportsManagement(base, key))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/p/v1/capabilities", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
    }

    @Test
    fun anOlderBridgeOrAPartialAdvertisementDisablesManagement() {
        server.enqueue(json(200, """{"features":{},"endpoints":{}}"""))
        server.enqueue(json(200, capabilities(archive = "true")))
        server.enqueue(json(200, capabilities(delete = false)))
        server.enqueue(json(200, capabilities(patchMethod = "POST")))
        server.enqueue(json(500, capabilities()))
        server.enqueue(json(200, "not json"))

        repeat(6) { assertFalse(client.supportsManagement(base, key)) }
    }

    @Test
    fun archiveSendsExactlyTheArchivedFlag() {
        server.enqueue(json(200, """{"object":"hermes.session","session":{"id":"s 1"}}"""))
        server.enqueue(json(200, """{"object":"hermes.session","session":{"id":"s 1"}}"""))

        assertEquals(SessionManageResult.Success, client.setArchived(base, key, "s 1", archived = true))
        assertEquals(SessionManageResult.Success, client.setArchived(base, key, "s 1", archived = false))
        val first = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertEquals("/p/api/sessions/s%201", first.requestUrl!!.encodedPath)
        assertEquals(JSONObject("""{"archived":true}""").toString(), JSONObject(first.body.readUtf8()).toString())
        assertEquals("""{"archived":false}""", server.takeRequest().body.readUtf8())
    }

    @Test fun renameChecksExactSessionAndTitleAndNeverRetriesAWrite() {
        server.enqueue(json(200, """{"object":"hermes.session","session":{"id":"s1","title":"New title"}}"""))
        assertEquals(SessionManageResult.Success, client.rename(base, key, "s1", "New title"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/p/api/sessions/s1", request.path)
        assertEquals(setOf("title"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
        server.enqueue(json(200, """{"object":"hermes.session","session":{"id":"other","title":"New title"}}"""))
        assertTrue(client.rename(base, key, "s1", "New title") is SessionManageResult.Failure)
        server.enqueue(json(503, "{}"))
        assertTrue(client.rename(base, key, "s1", "New title") is SessionManageResult.Failure)
        assertTrue(client.rename(base, key, "s1", "\n") is SessionManageResult.Failure)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun deleteSendsNoBodyAndMapsTheAnswers() {
        server.enqueue(json(200, """{"object":"hermes.session.deleted","id":"s1","deleted":true}"""))
        server.enqueue(json(200, """{"object":"hermes.session.deleted","id":"s1","deleted":false}"""))
        server.enqueue(json(409, """{"error":{"code":"session_active_turn"}}"""))
        server.enqueue(json(404, """{"error":{"code":"session_not_found"}}"""))
        server.enqueue(json(403, """{"error":{"code":"endpoint_out_of_scope"}}"""))
        server.enqueue(json(200, """{"object":"something.else"}"""))

        assertEquals(SessionManageResult.Success, client.delete(base, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.NotFound), client.delete(base, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.ActiveTurn), client.delete(base, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.NotFound), client.delete(base, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(base, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(base, key, "s1"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun aRedirectIsNotFollowedAndAWriteIsNotRepeated() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))

        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(base, key, "s1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun plainHttpAndUnsafeIdsNeverSendARequest() {
        val http = base.replace("https://", "http://")
        assertFalse(client.supportsManagement(http, key))
        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(http, key, "s1"))
        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(base, key, "a\u0000b"))
        assertEquals(SessionManageResult.Failure(SessionManageError.Failed), client.delete(base, key, " "))
        assertEquals(0, server.requestCount)
    }
}
