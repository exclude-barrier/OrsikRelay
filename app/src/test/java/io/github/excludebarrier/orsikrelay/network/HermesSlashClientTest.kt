package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommand
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsResult
import io.github.excludebarrier.orsikrelay.domain.chat.SessionModelOption
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandResult

class HermesSlashClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HermesSlashClient
    private val key = SecretValue("synthetic-test-credential")

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = HermesSlashClient(OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
    }

    @After fun tearDown() { server.shutdown() }

    private fun capabilities(title: Boolean = false): JSONObject {
        val endpoints = JSONObject()
        SlashCommand.entries.forEach {
            endpoints.put(it.endpoint, JSONObject().put("method", it.method).put("path", it.path))
        }
        endpoints.put("model_options", JSONObject().put("method", "GET").put("path", "/api/model/options"))
        return JSONObject().put("endpoints", endpoints)
            .put("features", JSONObject().put("session_title", title))
    }

    private fun enqueue(root: JSONObject) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(root.toString()))
    }

    @Test fun forkSendsEmptyObjectAndVerifiesParentBeforeOpeningChild() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
            .setBody("""{"object":"hermes.session","session":{"id":"child","parent_session_id":"s1"}}"""))
        assertEquals(SlashCommandResult.OpenSession("child"),
            client.fork(server.url("/prefix").toString(), key, "s1"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/prefix/api/sessions/s1/fork", request.path)
        assertEquals("{}", request.body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
            .setBody("""{"object":"hermes.session","session":{"id":"child","parent_session_id":"other"}}"""))
        assertEquals(SlashCommandResult.Failed,
            client.fork(server.url("/").toString(), key, "s1"))
        assertEquals(2, server.requestCount)
    }

    @Test fun modelsReadStringIdsAndLockOnlyTheExactSessionWithClosedBody() {
        enqueue(JSONObject("""{"providers":[{"slug":"provider","models":["org/model-a","model-b"],"api_url":"not-used"}]}"""))
        val loaded = client.models(server.url("/prefix").toString(), key)
            as ModelOptionsResult.Loaded
        assertEquals(listOf("provider::org/model-a", "provider::model-b"), loaded.options.map { it.qualified })
        assertEquals("/prefix/api/model/options", server.takeRequest().path)
        enqueue(JSONObject("""{"object":"hermes.session.model_lock","session_id":"s1","runtime":{"model_lock":"accepted","requested":{"provider":"provider","model":"org/model-a"}}}"""))
        assertEquals(SlashCommandResult.Done,
            client.lockModel(server.url("/prefix").toString(), key, "s1", loaded.options.first()))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/prefix/api/sessions/s1/model", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("provider", "model"), body.keys().asSequence().toSet())
        assertEquals("org/model-a", body.getString("model"))
    }

    @Test fun malformedModelCatalogAndUnacknowledgedLockNeverReportSuccess() {
        enqueue(JSONObject("""{"providers":[{"slug":"provider","models":[{"id":"fake"}]}]}"""))
        assertEquals(ModelOptionsResult.Failed,
            client.models(server.url("/").toString(), key))
        enqueue(JSONObject("""{"object":"hermes.session.model_lock","session_id":"other","runtime":{"model_lock":"accepted"}}"""))
        val option = SessionModelOption("provider", "model")
        assertEquals(SlashCommandResult.Failed,
            client.lockModel(server.url("/").toString(), key, "s1", option))
        for (code in listOf(401, 404, 409, 429, 503, 302)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/elsewhere")))
            assertEquals(SlashCommandResult.Failed,
                client.lockModel(server.url("/").toString(), key, "s1", option))
        }
        assertEquals(8, server.requestCount)
    }

    @Test fun capabilityRoutesAreExactAndOldArchiveOnlyBridgeDoesNotOfferTitle() {
        enqueue(capabilities())
        val commands = client.available(server.url("/prefix").toString(), key)
        assertEquals(SlashCommand.entries.toSet() - SlashCommand.TITLE, commands)
        assertEquals("/prefix/v1/capabilities", server.takeRequest().path)
        enqueue(capabilities(title = true))
        assertEquals(SlashCommand.entries.toSet(), client.available(server.url("/").toString(), key))
    }

    @Test fun wrongMethodAndMissingModelOptionsDisableCommands() {
        val root = capabilities(true)
        root.getJSONObject("endpoints").getJSONObject("run_stop").put("method", "GET")
        root.getJSONObject("endpoints").remove("model_options")
        enqueue(root)
        val commands = client.available(server.url("/").toString(), key)
        assertFalse(SlashCommand.STOP in commands)
        assertFalse(SlashCommand.MODEL in commands)
    }

    @Test fun failuresAndRedirectsFailClosedWithoutFollowingOrRetrying() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertTrue(client.available(server.url("/").toString(), key).isEmpty())
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.available(server.url("/").toString(), key).isEmpty())
        enqueue(JSONObject())
        assertTrue(client.available(server.url("/").toString(), key).isEmpty())
        assertEquals(3, server.requestCount)
    }
}
