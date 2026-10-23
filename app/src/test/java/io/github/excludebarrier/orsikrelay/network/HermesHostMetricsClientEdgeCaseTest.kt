package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adversarial inputs for the tolerant `GET /v1/host/metrics` parser: a field
 * of the wrong shape is dropped, but no out-of-range number, control
 * character or unbounded body may turn into a plausible-looking value.
 */
class HermesHostMetricsClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates
    private lateinit var client: HermesHostMetricsClient
    private val key = SecretValue("fixture_key_123")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
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

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun read() = client.read(base, key)

    private fun ready(): HostMetrics = (read() as HostMetricsResult.Ready).metrics

    private fun emptyMetrics() = HostMetrics(null, null, null, null, null, emptyList())

    @Test
    fun aHugeExponentIsNoByteCountNotZero() {
        server.enqueue(json("""{"object":"orsik.host.metrics","memory":{"used_bytes":1e400,"total_bytes":1e400}}"""))

        val metrics = ready()

        // SUSPECTED BUG: longOrNull() calls Number.toLong() before the >= 0
        // check, so an out-of-range number is truncated instead of rejected:
        // 1e400 is a BigDecimal on the JVM and reads as 0 bytes (on Android it
        // is a Double and reads as Long.MAX_VALUE).
        assertNull("used", metrics.memoryUsedBytes)
        assertNull("total", metrics.memoryTotalBytes)
    }

    @Test
    fun anIntegerBeyondTheLongRangeIsNoByteCount() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics",
             "memory":{"used_bytes":9223372036854775808,"total_bytes":-9223372036854775809}}
        """.trimIndent()))

        val metrics = ready()

        assertNull("one past Long.MAX_VALUE", metrics.memoryUsedBytes)
        // SUSPECTED BUG: BigInteger.longValue() wraps before the >= 0 check,
        // so a huge negative count becomes the positive Long.MAX_VALUE.
        assertNull("one past Long.MIN_VALUE", metrics.memoryTotalBytes)
    }

    @Test
    fun negativeNumbersAreNeverValues() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics",
             "cpu":{"percent":-0.5,"cores":-8},
             "memory":{"used_bytes":-1,"total_bytes":-2},
             "gpu":{"name":"gpu","utilization_percent":-5,"memory_used_bytes":-3,"memory_total_bytes":-4},
             "services":[{"name":"svc","active":true}]}
        """.trimIndent()))

        assertEquals(
            HostMetricsResult.Ready(
                HostMetrics(null, null, null, null, null, listOf(ServiceState("svc", true)))
            ),
            read()
        )
    }

    @Test
    fun usedMemoryAboveTotalIsKeptAsReported() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics","memory":{"used_bytes":900,"total_bytes":100}}
        """.trimIndent()))

        val metrics = ready()

        // The transport is not the place to invent a memory invariant; the UI
        // already clamps the fill to 0..1.
        assertEquals(900L, metrics.memoryUsedBytes)
        assertEquals(100L, metrics.memoryTotalBytes)
    }

    @Test
    fun cpuPercentMustBeARealNumberInRange() {
        listOf(
            "0" to 0.0,
            "100" to 100.0,
            "100.0001" to null,
            "-0.1" to null,
            "\"50\"" to null,
            "null" to null,
            "[50]" to null,
            "true" to null,
            "1e400" to null,
            "NaN" to null
        ).forEach { (raw, expected) ->
            server.enqueue(json("""{"object":"orsik.host.metrics","cpu":{"percent":$raw}}"""))
            assertEquals("percent $raw", expected, ready().cpuPercent)
        }
    }

    @Test
    fun coresMustBeAPositiveInt() {
        listOf(
            "6" to 6,
            "0" to null,
            "-1" to null,
            "2147483648" to null,
            "1e400" to null,
            "\"6\"" to null,
            "[6]" to null
        ).forEach { (raw, expected) ->
            server.enqueue(json("""{"object":"orsik.host.metrics","cpu":{"cores":$raw}}"""))
            assertEquals("cores $raw", expected, ready().cores)
        }
    }

    @Test
    fun gpuUtilizationIsClampedButAnUnusableNumberDropsTheWholeGpu() {
        listOf(
            "150" to 100,
            "-10" to 0,
            "50.0" to null,
            "1e400" to null,
            "\"50\"" to null
        ).forEach { (raw, expected) ->
            server.enqueue(json("""
                {"object":"orsik.host.metrics",
                 "gpu":{"name":"gpu","utilization_percent":$raw,"memory_used_bytes":1,"memory_total_bytes":2}}
            """.trimIndent()))
            assertEquals("utilization $raw", expected, ready().gpu?.utilizationPercent)
        }
    }

    @Test
    fun wrongTypesDropOnlyTheAffectedField() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics",
             "cpu":"fast",
             "memory":{"used_bytes":"1","total_bytes":[2]},
             "gpu":"none",
             "services":{}}
        """.trimIndent()))
        assertEquals(HostMetricsResult.Ready(emptyMetrics()), read())

        server.enqueue(json("""
            {"object":"orsik.host.metrics",
             "cpu":{"percent":{"value":50},"cores":[]},
             "gpu":{"name":5,"utilization_percent":"50","memory_used_bytes":{},"memory_total_bytes":null},
             "services":[null,{"n":5},{"name":"named","active":"true"},{"name":"good","active":false}]}
        """.trimIndent()))
        assertEquals(
            HostMetricsResult.Ready(
                HostMetrics(null, null, null, null, null, listOf(ServiceState("good", false)))
            ),
            read()
        )
    }

    @Test
    fun duplicateKeysAreRejectedByTheParser() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics","object":"orsik.host.metrics","cpu":{"percent":10}}
        """.trimIndent()))

        // The JVM org.json parser rejects duplicate keys, so the ambiguous
        // answer fails closed instead of silently picking one of the values.
        assertEquals(HostMetricsResult.Failed, read())
    }

    @Test
    fun emptyAndNonObjectBodiesFail() {
        listOf("", "   ", "null", "[]", "}{", "\"text\"", "42").forEach { raw ->
            server.enqueue(json(raw))
            assertEquals("body <$raw>", HostMetricsResult.Failed, read())
        }
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(HostMetricsResult.Failed, read())
    }

    @Test
    fun bodiesOverTheBoundFailEvenWhenChunked() {
        val oversized = """{"object":"orsik.host.metrics","padding":"${"x".repeat(70 * 1024)}"}"""

        server.enqueue(json(oversized))
        assertEquals(HostMetricsResult.Failed, read())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(oversized, 8192)
        )
        assertEquals(HostMetricsResult.Failed, read())
    }

    @Test
    fun anOversizedCapabilitiesBodyIsNotSupported() {
        val oversized = """
            {"features":{"host_metrics":true},
             "endpoints":{"host_metrics":{"method":"GET","path":"/v1/host/metrics"}},
             "padding":"${"x".repeat(70 * 1024)}"}
        """.trimIndent()

        server.enqueue(json(oversized))

        assertFalse(client.supports(base, key))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aWrongOrMissingContentTypeIsRejected() {
        val valid = """{"object":"orsik.host.metrics","cpu":{"percent":27.6}}"""

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(valid)
        )
        // SUSPECTED BUG: this client (like SessionManage and RunResolve) never
        // calls requireJsonContentType, so a 200 of any media type carrying
        // JSON is parsed as the contract; the other one-shot clients reject it.
        assertEquals(HostMetricsResult.Failed, read())

        server.enqueue(MockResponse().setResponseCode(200).setBody(valid))
        assertEquals(HostMetricsResult.Failed, read())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(valid)
        )
        assertEquals(HostMetricsResult.Ready(HostMetrics(27.6, null, null, null, null, emptyList())), read())
    }

    @Test
    fun unexpectedStatusesFailAndRedirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setResponseCode(301).setHeader("Location", server.url("/moved"))
        )
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/plain")
        )
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"orsik.host.metrics"}""")
        )

        repeat(4) { assertEquals(HostMetricsResult.Failed, read()) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun moreThanEightServicesAreBoundedNotUnbounded() {
        val services = (0 until 12).joinToString(",", prefix = "[", postfix = "]") {
            """{"name":"s$it","active":${it % 2 == 0}}"""
        }
        server.enqueue(json("""{"object":"orsik.host.metrics","services":$services}"""))

        val metrics = ready()

        assertEquals(8, metrics.services.size)
        assertEquals((0 until 8).map { "s$it" }, metrics.services.map { it.name })
    }

    @Test
    fun namesAllowUnicodeButRejectC0ControlsDelAndExcessLength() {
        val sixtyFour = "a".repeat(64)
        val sixtyFive = "b".repeat(65)
        server.enqueue(json("""
            {"object":"orsik.host.metrics","services":[
              {"name":"$sixtyFour","active":true},
              {"name":"$sixtyFive","active":true},
              {"name":"Grüße ✓","active":true},
              {"name":"bad\u0001","active":true},
              {"name":"del\u007f","active":true}
            ]}
        """.trimIndent()))

        val metrics = ready()

        assertEquals(
            listOf(ServiceState(sixtyFour, true), ServiceState("Grüße ✓", true)),
            metrics.services
        )
    }

    @Test
    fun c1ControlCharactersInANameAreRejectedToo() {
        server.enqueue(json("""
            {"object":"orsik.host.metrics","services":[{"name":"c1\u0085name","active":true}]}
        """.trimIndent()))

        // SUSPECTED BUG: isSafeServerString() only checks code < 0x20 and 0x7F,
        // while its KDoc promises no ISO control character; C1 controls
        // (U+0080..U+009F) pass and reach the service name on the Home card.
        assertTrue(ready().services.isEmpty())
    }

    @Test
    fun aSlowBodyFailsClosedInsteadOfHanging() {
        val slowClient = HermesHostMetricsClient(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build()
        )
        val slowBody = """{"object":"orsik.host.metrics","padding":"${"x".repeat(8 * 1024)}"}"""
        server.enqueue(json(slowBody).throttleBody(32, 100, TimeUnit.MILLISECONDS))

        assertEquals(HostMetricsResult.Failed, slowClient.read(base, key))
    }
}
