package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HermesRunResolveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesRunResolveClient
    private val key = SecretValue("fixture_key_123")
    private val lost = LostRun("adm_1", 4)

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesRunResolveClient(
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

    @Test
    fun sendsExactlyTheAcknowledgementKeyToTheRunsRoute() {
        server.enqueue(json(200, """{"run_id":"run_1","status":"terminal","outcome":"interrupted"}"""))

        assertEquals(RunResolveResult.Resolved, client.resolve(base, key, "run_1", lost))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/p/v1/runs/run_1/resolve-unknown", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("admission_id", "execution_generation"), body.keys().asSequence().toSet())
        assertEquals("adm_1", body.getString("admission_id"))
        assertEquals(4, body.getInt("execution_generation"))
    }

    @Test
    fun aStaleGenerationIsReportedAndEverythingElseFails() {
        server.enqueue(json(409, """{"error":{"message":"stale_generation","code":"stale_generation"}}"""))
        server.enqueue(json(404, """{"error":{"code":"not_found"}}"""))
        server.enqueue(json(200, """{"run_id":"other_run"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/x")))

        assertEquals(RunResolveResult.Failure(RunResolveError.Stale), client.resolve(base, key, "run_1", lost))
        repeat(3) {
            assertEquals(RunResolveResult.Failure(RunResolveError.Failed), client.resolve(base, key, "run_1", lost))
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun unsafeInputNeverSendsARequest() {
        val http = base.replace("https://", "http://")
        val failed = RunResolveResult.Failure(RunResolveError.Failed)
        assertEquals(failed, client.resolve(http, key, "run_1", lost))
        assertEquals(failed, client.resolve(base, key, " ", lost))
        assertEquals(failed, client.resolve(base, key, "run_1", LostRun("a\u0000b", 1)))
        assertEquals(failed, client.resolve(base, key, "run_1", LostRun("adm_1", -1)))
        assertEquals(0, server.requestCount)
    }
}
