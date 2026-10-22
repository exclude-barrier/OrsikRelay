package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Adversarial inputs for `GET /v1/runs/{run_id}` beyond the happy-path
 * contract: the lost-run acknowledgement key is strictly typed and bounded,
 * bodies are bounded, redirects are never followed and a slow body maps to
 * the closed Timeout reason.
 */
class HermesRunStatusClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesRunStatusClient

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
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesRunStatusClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun read(): RunStatusResult =
        client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

    private fun unknown(lostRun: LostRun?): RunStatusResult =
        RunStatusResult.Known(RunServerStatus.UNKNOWN, lostRun)

    private fun unknownBody(admissionJson: String, generationJson: String): String =
        "{\"run_id\":\"$RUN_ID\",\"status\":\"unknown\"," +
            "\"admission_id\":$admissionJson,\"execution_generation\":$generationJson}"

    @Test
    fun lostRunFieldsAreStrictlyBoundedAndTyped() {
        val longId = "a".repeat(256)
        val tooLongId = "a".repeat(257)

        listOf(
            unknownBody("\"$longId\"", "1") to LostRun(longId, 1),
            unknownBody("\"$tooLongId\"", "1") to null,
            unknownBody("\"adm\u0085id\"", "1") to null,
            unknownBody("\"   \"", "1") to null,
            unknownBody("\"\"", "1") to null,
            unknownBody("\"adm_1\"", "0") to LostRun("adm_1", 0),
            unknownBody("\"adm_1\"", "-1") to null,
            unknownBody("\"adm_1\"", "2147483648") to null,
            unknownBody("\"adm_1\"", "4.0") to null,
            unknownBody("\"adm_1\"", "\"4\"") to null,
            unknownBody("\"adm_1\"", "true") to null,
            unknownBody("\"adm_1\"", "null") to null,
            unknownBody("123", "1") to null,
            unknownBody("[\"adm_1\"]", "1") to null
        ).forEach { (body, expected) ->
            server.enqueue(jsonResponse(body))
            assertEquals("body $body", unknown(expected), read())
        }
    }

    @Test
    fun duplicateKeysAreRejectedNotGuessed() {
        server.enqueue(
            jsonResponse("{\"run_id\":\"$RUN_ID\",\"status\":\"running\",\"status\":\"completed\"}")
        )

        assertEquals(RunStatusResult.Failure(RunStatusError.IncompatibleApi), read())
    }

    @Test
    fun oversizedKnownAndChunkedBodiesAreIncompatible() {
        val oversized = "{\"run_id\":\"$RUN_ID\",\"status\":\"running\"," +
            "\"padding\":\"${"x".repeat(600 * 1024)}\"}"

        server.enqueue(jsonResponse(oversized))
        assertEquals(RunStatusResult.Failure(RunStatusError.IncompatibleApi), read())

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setChunkedBody(oversized, 8192)
        )
        assertEquals(RunStatusResult.Failure(RunStatusError.IncompatibleApi), read())
    }

    @Test
    fun unexpectedStatusesAndRedirectsMapToHttp() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/moved")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/plain"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))

        repeat(5) {
            assertEquals(RunStatusResult.Failure(RunStatusError.Http), read())
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun aSlowBodyMapsToTimeout() {
        val slowClient = HermesRunStatusClient(
            trustedClient.newBuilder()
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build()
        )
        val slowBody = "{\"run_id\":\"$RUN_ID\",\"status\":\"running\"," +
            "\"padding\":\"${"x".repeat(8 * 1024)}\"}"
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(slowBody)
                .throttleBody(32, 100, TimeUnit.MILLISECONDS)
        )

        assertEquals(
            RunStatusResult.Failure(RunStatusError.Timeout),
            slowClient.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)
        )
    }

    @Test
    fun anUnicodeRunIdIsOneEncodedSegmentAndMustBeEchoed() {
        val runId = "run-ü"
        server.enqueue(jsonResponse("{\"run_id\":\"$runId\",\"status\":\"running\"}"))

        assertEquals(
            RunStatusResult.Known(RunServerStatus.RUNNING),
            client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), runId)
        )
        val request = server.takeRequest()
        assertEquals("/p/android/v1/runs/run-%C3%BC", request.requestUrl!!.encodedPath)
    }

    @Test
    fun aStatusWithControlCharactersIsIncompatible() {
        server.enqueue(jsonResponse("""{"run_id":"$RUN_ID","status":"run\u0001ning"}"""))

        assertEquals(RunStatusResult.Failure(RunStatusError.IncompatibleApi), read())
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val RUN_ID = "run_synthetic"
    }
}
