package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.device.DeviceSelfRevokeError
import io.github.excludebarrier.orsikrelay.domain.device.DeviceSelfRevokeResult
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
 * Mission 017A: transport contract of the single authenticated
 * `POST /v1/devices/self/revoke` write. Wire format per server contract
 * v1 (docs/mobile-wire-contract.md §2.4): `Authorization: Bearer <device
 * key>`, no request body (exact empty JSON object, no Content-Type); 200
 * with EXACTLY `{"revoked":bool,"device_id":<uuid>}`; 401 is the closed
 * invalid-credentials answer (no credential-class oracle); 403/404/429
 * and every other status map to closed reasons.
 */
class HermesDeviceSelfRevokeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesDeviceSelfRevokeClient

    @Before
    fun setUp() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesDeviceSelfRevokeClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptedRevokeSendsExactPostPathEmptyBodyAndBearerHeaderOnly() {
        server.enqueue(jsonResponse(revokedBody(true)))

        val result = client.revokeSelf(baseUrl(), SecretValue(DEVICE_KEY))

        assertEquals(
            DeviceSelfRevokeResult.Revoked(true, DEVICE_ID),
            result
        )
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        assertEquals("/p/android/v1/devices/self/revoke", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $DEVICE_KEY", request.getHeader("Authorization"))
        // The server contract is bodyless. OkHttp still requires a RequestBody
        // for POST, so the client must send exactly zero bytes and no media type.
        assertEquals(0L, request.body.size)
        assertEquals("", request.body.readUtf8())
        assertNull(request.getHeader("Content-Type"))
        assertFalse(request.requestUrl!!.toString().contains(DEVICE_KEY))
    }

    @Test
    fun revokedTrueAndFalseBothMapToRevokedWithDeviceId() {
        server.enqueue(jsonResponse(revokedBody(true)))
        assertEquals(DeviceSelfRevokeResult.Revoked(true, DEVICE_ID), revoke())

        server.enqueue(jsonResponse(revokedBody(false)))
        assertEquals(DeviceSelfRevokeResult.Revoked(false, DEVICE_ID), revoke())
    }

    @Test
    fun missingOrWrongTypedFieldsAreIncompatibleApi() {
        listOf(
            "{}",
            "{\"revoked\":true}",
            "{\"device_id\":\"$DEVICE_ID\"}",
            "{\"revoked\":\"true\",\"device_id\":\"$DEVICE_ID\"}",
            "{\"revoked\":true,\"device_id\":42}",
            "{\"revoked\":true,\"device_id\":\"not-a-uuid\"}",
            "[\"true\",\"$DEVICE_ID\"]",
            "\"true\"",
            "null"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi),
                revoke()
            )
        }
    }

    @Test
    fun extraFieldIsIncompatibleApi() {
        server.enqueue(jsonResponse(revokedBody(true).replace("}", ",\"extra\":1}")))

        assertEquals(
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi),
            revoke()
        )
    }

    @Test
    fun nonJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi),
                revoke()
            )
        }
        val oversized = "{\"revoked\":true,\"padding\":\"" + "x".repeat(600 * 1024) + "\"}"
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi),
            revoke()
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApiButJsonParametersAreAllowed() {
        listOf("text/html", "application/xml", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(revokedBody(true))
            )
            assertEquals(
                "content type $contentType must be rejected",
                DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi),
                revoke()
            )
        }
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(revokedBody(true))
        )
        assertEquals(DeviceSelfRevokeResult.Revoked(true, DEVICE_ID), revoke())
    }

    @Test
    fun unauthorizedIsClosedInvalidCredentialsWithoutReadingTheBody() {
        // The 401 answer does not distinguish the credential class (API
        // key, unknown device key, revoked device key): same answer, no
        // oracle. The client must not read or propagate the body.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody(
                    "{\"error\":{\"code\":\"gateway_auth_failed\"," +
                        "\"message\":\"auth failure for key $DEVICE_KEY\"}}"
                )
        )

        val result = revoke()

        assertEquals(
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidCredentials),
            result
        )
        assertFalse(result.toString().contains(DEVICE_KEY))
    }

    @Test
    fun otherHttpStatusesMapToClosedErrorsWithoutRetry() {
        listOf(
            403 to DeviceSelfRevokeError.Forbidden,
            404 to DeviceSelfRevokeError.NotFound,
            429 to DeviceSelfRevokeError.RateLimited,
            500 to DeviceSelfRevokeError.Http,
            503 to DeviceSelfRevokeError.Http
        ).forEach { (code, reason) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody("{\"error\":{\"message\":\"dynamic server detail $code\"}}")
            )
            assertEquals(
                DeviceSelfRevokeResult.Failure(reason),
                revoke()
            )
        }
        // One request per failure: no automatic retry of the write.
        assertEquals(5, server.requestCount)
    }

    @Test
    fun serverErrorTextNeverReachesTheResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody(
                    "{\"error\":{\"message\":\"device not found: $DEVICE_ID, key $DEVICE_KEY\"}}"
                )
        )

        val result = revoke()

        assertEquals(
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.NotFound),
            result
        )
        assertFalse(result.toString().contains("device not found"))
        assertFalse(result.toString().contains(DEVICE_KEY))
        assertFalse(result.toString().contains(DEVICE_ID))
    }

    @Test
    fun redirectIsNotFollowedAndMapsToHttp() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(revokedBody(true)))

        val result = revoke()

        assertEquals(DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Http), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsErrorWithoutLeakingKey() {
        server.enqueue(jsonResponse(revokedBody(true)))
        val untrusted = HermesDeviceSelfRevokeClient(OkHttpClient.Builder().build())

        val result = untrusted.revokeSelf(baseUrl(), SecretValue(DEVICE_KEY))

        assertEquals(DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Tls), result)
        assertFalse(result.toString().contains(DEVICE_KEY))
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            jsonResponse(revokedBody(true))
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )

        val result = HermesDeviceSelfRevokeClient(shortClient)
            .revokeSelf(baseUrl(), SecretValue(DEVICE_KEY))

        assertEquals(DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Timeout), result)
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.revokeSelf(unavailable, SecretValue(DEVICE_KEY))

        assertTrue(
            "expected a closed transport failure, got $result",
            result is DeviceSelfRevokeResult.Failure
        )
        assertFalse(result.toString().contains(DEVICE_KEY))
        assertFalse(result.toString().contains(unavailable))
    }

    @Test
    fun localValidationFailuresSendNoRequest() {
        listOf(
            "http://example.invalid" to "http-url",
            "not-a-url" to "bad-url",
            baseUrl() to "blank-key",
            baseUrl() to "whitespace-key",
            baseUrl() to "overlong-key",
            baseUrl() to "control-char-key",
            baseUrl() to "wrong-prefix-key",
            baseUrl() to "wrong-length-key",
            baseUrl() to "non-base64url-key"
        ).forEach { (url, label) ->
            val key = when (label) {
                "blank-key" -> ""
                "whitespace-key" -> "has whitespace"
                "overlong-key" -> "k".repeat(4097)
                "control-char-key" -> "key\u0001"
                "wrong-prefix-key" -> "xx_0123456789012345678901234567890123456789012"
                "wrong-length-key" -> DEVICE_KEY.dropLast(1)
                "non-base64url-key" -> "hd_!123456789012345678901234567890123456789012"
                else -> DEVICE_KEY
            }
            val result = client.revokeSelf(url, SecretValue(key))
            assertEquals(
                "$label must fail closed locally",
                DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyNeverAppearsInUrlBodyOrResult() {
        server.enqueue(jsonResponse(revokedBody(true)))

        val result = client.revokeSelf(baseUrl(), SecretValue(DEVICE_KEY))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(DEVICE_KEY))
        assertFalse(request.body.readUtf8().contains(DEVICE_KEY))
        assertFalse(result.toString().contains(DEVICE_KEY))
    }

    private fun revoke(): DeviceSelfRevokeResult =
        client.revokeSelf(baseUrl(), SecretValue(DEVICE_KEY))

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun revokedBody(revoked: Boolean): String =
        "{\"revoked\":$revoked,\"device_id\":\"$DEVICE_ID\"}"

    private companion object {
        const val DEVICE_ID = "3f9c2a1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        const val DEVICE_KEY = "hd_0123456789012345678901234567890123456789012"
    }
}
