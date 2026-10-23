package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.host.GpuMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesHostMetricsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesHostMetricsClient
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
        client = HermesHostMetricsClient(
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
    fun aFullSnapshotIsParsed() {
        server.enqueue(json(200, """
            {"object":"orsik.host.metrics","cpu":{"percent":27.6,"cores":6},
             "memory":{"total_bytes":32000,"used_bytes":16000},
             "gpu":{"name":"RTX 4090","utilization_percent":37,"memory_used_bytes":1,"memory_total_bytes":2},
             "services":[{"name":"hermes-gateway","active":true},{"name":"orsik-mobile-bridge","active":false}],
             "collected_at":1.0}
        """.trimIndent()))

        assertEquals(
            HostMetricsResult.Ready(
                HostMetrics(27.6, 6, 16000, 32000, GpuMetrics("RTX 4090", 37, 1, 2),
                    listOf(ServiceState("hermes-gateway", true), ServiceState("orsik-mobile-bridge", false)))
            ),
            client.read(base, key)
        )
        val request = server.takeRequest()
        assertEquals("/p/v1/host/metrics", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
    }

    @Test
    fun missingOrOddFieldsBecomeNoValueNotAnError() {
        server.enqueue(json(200, """
            {"object":"orsik.host.metrics","cpu":{"percent":null,"cores":6},"memory":null,"gpu":null,
             "services":[{"name":"ok","active":true},{"name":"bad\u0001","active":true},{"name":"x"}]}
        """.trimIndent()))

        assertEquals(
            HostMetricsResult.Ready(HostMetrics(null, 6, null, null, null, listOf(ServiceState("ok", true)))),
            client.read(base, key)
        )
    }

    @Test
    fun aWrongObjectOrStatusFails() {
        server.enqueue(json(200, """{"object":"something"}"""))
        server.enqueue(json(403, """{"error":{"code":"endpoint_out_of_scope"}}"""))
        assertEquals(HostMetricsResult.Failed, client.read(base, key))
        assertEquals(HostMetricsResult.Failed, client.read(base, key))
    }

    @Test
    fun supportNeedsTheFeatureAndTheExactEndpoint() {
        server.enqueue(json(200, """{"features":{"host_metrics":true},"endpoints":{"host_metrics":{"method":"GET","path":"/v1/host/metrics"}}}"""))
        server.enqueue(json(200, """{"features":{"host_metrics":true},"endpoints":{}}"""))
        server.enqueue(json(200, """{"features":{},"endpoints":{"host_metrics":{"method":"GET","path":"/v1/host/metrics"}}}"""))
        assertTrue(client.supports(base, key))
        assertFalse(client.supports(base, key))
        assertFalse(client.supports(base, key))
        assertEquals("/p/v1/capabilities", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun plainHttpSendsNothing() {
        val http = base.replace("https://", "http://")
        assertFalse(client.supports(http, key))
        assertEquals(HostMetricsResult.Failed, client.read(http, key))
        assertEquals(0, server.requestCount)
    }
}
