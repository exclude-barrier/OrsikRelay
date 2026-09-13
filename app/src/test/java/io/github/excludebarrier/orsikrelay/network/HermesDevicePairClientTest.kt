package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.device.DevicePairError
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairResult
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mission 017A: transport contract of the single UNAUTHENTICATED
 * `POST /v1/devices/pair` write. Wire format per server contract v1
 * (read-only reference in internal task log): exact body `{"code":"<code>"}` with
 * `Content-Type: application/json` and NO Authorization header; 201 with
 * EXACTLY the closed credential field set; 400/409/429/500 with closed
 * error codes. The code is normalized locally; the key is `hd_` + 43
 * base64url (46 total); permissions are exactly approvals/chat/runs.
 */
class HermesDevicePairClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesDevicePairClient

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
        client = HermesDevicePairClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptedPairSendsExactPostPathBodyAndNoAuthorizationHeader() {
        server.enqueue(jsonResponse(credentialBody()))

        val result = client.pairDevice(baseUrl(), DASHED_CODE)

        assertTrue(result is DevicePairResult.Paired)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        assertEquals("/p/android/v1/devices/pair", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        // Unauthenticated: NO Authorization header is sent.
        assertNull(request.getHeader("Authorization"))
        val sentBody = JSONObject(request.body.readUtf8())
        assertEquals(1, sentBody.length())
        // The normalized canonical code (dashes removed) is what goes on the wire.
        assertEquals(CANONICAL_CODE, sentBody.getString("code"))
        assertEquals(
            "application/json",
            request.getHeader("Content-Type")?.substringBefore(';')?.trim()
        )
        assertFalse(request.requestUrl!!.toString().contains(CANONICAL_CODE))
    }

    @Test
    fun exact201CredentialMapsToPairedWithRedactedKey() {
        server.enqueue(jsonResponse(credentialBody()))

        val result = client.pairDevice(baseUrl(), CANONICAL_CODE)

        val paired = result as? DevicePairResult.Paired
        assertNotNull(paired)
        val cred = paired!!.credential
        assertEquals(DEVICE_ID, cred.deviceId)
        assertEquals("fixture-profile", cred.profile)
        assertEquals("fixture-label", cred.label)
        assertEquals(setOf("approvals", "chat", "runs"), cred.permissions.toSet())
        assertEquals(1757000000.125, cred.issuedAt, 0.0)
        assertTrue(cred.deviceKey.useRaw { it == DEVICE_KEY })
        // The raw key must never appear in the result rendering.
        assertFalse(result.toString().contains(DEVICE_KEY))
    }

    @Test
    fun permissionsInAnyOrderAreAccepted() {
        val body = credentialBody().replace(
            "\"permissions\":[\"approvals\",\"chat\",\"runs\"]",
            "\"permissions\":[\"runs\",\"approvals\",\"chat\"]"
        )
        server.enqueue(jsonResponse(body))

        val result = client.pairDevice(baseUrl(), CANONICAL_CODE)

        assertTrue(result is DevicePairResult.Paired)
        assertEquals(
            setOf("approvals", "chat", "runs"),
            (result as DevicePairResult.Paired).credential.permissions.toSet()
        )
    }

    @Test
    fun extraOrMissingFieldIn201IsIncompatibleApi() {
        listOf(
            // missing device_key
            credentialBody().replace(",\"device_key\":\"$DEVICE_KEY\"", ""),
            // extra field
            credentialBody().replace("}", ",\"extra\":1}"),
            // missing label
            credentialBody().replace(",\"label\":\"fixture-label\"", "")
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun wrongObjectDiscriminatorIsIncompatibleApi() {
        val body = credentialBody().replace(
            "\"object\":\"hermes.device.credential\"",
            "\"object\":\"hermes.device\""
        )
        server.enqueue(jsonResponse(body))
        assertEquals(
            DevicePairResult.Failure(DevicePairError.IncompatibleApi),
            client.pairDevice(baseUrl(), CANONICAL_CODE)
        )
    }

    @Test
    fun malformedDeviceKeyIsIncompatibleApi() {
        listOf(
            // wrong prefix
            credentialBody().replace(DEVICE_KEY, "xx_0123456789012345678901234567890123456789012"),
            // 45 total (hd_ + 42)
            credentialBody().replace(DEVICE_KEY, "hd_012345678901234567890123456789012345678901"),
            // 47 total (hd_ + 44)
            credentialBody().replace(DEVICE_KEY, "hd_01234567890123456789012345678901234567890123"),
            // correct length but forbidden non-base64url character
            credentialBody().replace(DEVICE_KEY, "hd_!123456789012345678901234567890123456789012")
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun nonUuidDeviceIdAndNonNumericIssuedAtAreIncompatibleApi() {
        listOf(
            credentialBody().replace(DEVICE_ID, "not-a-uuid"),
            credentialBody().replace("1757000000.125", "\"1757000000\"")
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun wrongPermissionsSetIsIncompatibleApi() {
        listOf(
            credentialBody().replace("\"runs\"", "\"admin\""),
            credentialBody().replace(",\"runs\"", "") // missing one
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun nonJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
        val oversized = JSONObject()
            .put("object", "hermes.device.credential")
            .put("padding", "x".repeat(600 * 1024))
            .toString()
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            DevicePairResult.Failure(DevicePairError.IncompatibleApi),
            client.pairDevice(baseUrl(), CANONICAL_CODE)
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApi() {
        listOf("text/html", "application/xml", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(HermesDevicePairClient.CREATED_CODE)
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(credentialBody())
            )
            assertEquals(
                "content type $contentType must be rejected",
                DevicePairResult.Failure(DevicePairError.IncompatibleApi),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun closedErrorCodesMapWithoutRetry() {
        listOf(
            Triple(400, "pairing_code_invalid", DevicePairError.PairingCodeInvalid),
            Triple(400, "invalid_pair_request", DevicePairError.InvalidPairRequest),
            Triple(409, "device_limit_exceeded", DevicePairError.DeviceLimitExceeded),
            Triple(429, "pair_rate_limited", DevicePairError.PairRateLimited),
            Triple(500, "pair_failed", DevicePairError.PairFailed)
        ).forEach { (code, errorCode, expected) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(errorBody(errorCode))
            )
            assertEquals(
                "code $code / $errorCode",
                DevicePairResult.Failure(expected),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
        // One request per failure: the single-use pairing is never retried.
        assertEquals(5, server.requestCount)
    }

    @Test
    fun unknownErrorCodeOnClosedStatusIsIncompatibleApi() {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(errorBody("some_other_code"))
        )
        assertEquals(
            DevicePairResult.Failure(DevicePairError.IncompatibleApi),
            client.pairDevice(baseUrl(), CANONICAL_CODE)
        )
    }

    @Test
    fun rateLimitedWithoutTheClosedCodeIsIncompatibleApi() {
        // 429 without the closed `pair_rate_limited` code is a protocol
        // violation, not a rate-limit answer (fail-closed per contract).
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody(errorBody("some_other_code"))
        )
        assertEquals(
            DevicePairResult.Failure(DevicePairError.IncompatibleApi),
            client.pairDevice(baseUrl(), CANONICAL_CODE)
        )
    }

    @Test
    fun missingErrorCodeOnClosedStatusIsIncompatibleApi() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"dynamic detail\"}}")
        )
        assertEquals(
            DevicePairResult.Failure(DevicePairError.IncompatibleApi),
            client.pairDevice(baseUrl(), CANONICAL_CODE)
        )
    }

    @Test
    fun otherHttpStatusesMapToClosedErrors() {
        listOf(
            401 to DevicePairError.InvalidCredentials,
            403 to DevicePairError.InvalidCredentials,
            404 to DevicePairError.NotFound,
            503 to DevicePairError.Http
        ).forEach { (code, expected) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(errorBody("whatever"))
            )
            assertEquals(
                "code $code",
                DevicePairResult.Failure(expected),
                client.pairDevice(baseUrl(), CANONICAL_CODE)
            )
        }
    }

    @Test
    fun serverErrorTextNeverReachesTheResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"bad code $CANONICAL_CODE key $DEVICE_KEY\"}}")
        )

        val result = client.pairDevice(baseUrl(), CANONICAL_CODE)

        assertTrue(result is DevicePairResult.Failure)
        assertFalse(result.toString().contains(CANONICAL_CODE))
        assertFalse(result.toString().contains(DEVICE_KEY))
        assertFalse(result.toString().contains("bad code"))
    }

    @Test
    fun redirectIsNotFollowedAndMapsToHttp() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(credentialBody()))

        val result = client.pairDevice(baseUrl(), CANONICAL_CODE)

        assertEquals(DevicePairResult.Failure(DevicePairError.Http), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsError() {
        server.enqueue(jsonResponse(credentialBody()))
        val untrusted = HermesDevicePairClient(OkHttpClient.Builder().build())

        val result = untrusted.pairDevice(baseUrl(), CANONICAL_CODE)

        assertEquals(DevicePairResult.Failure(DevicePairError.Tls), result)
        assertFalse(result.toString().contains(CANONICAL_CODE))
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            jsonResponse(credentialBody())
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )

        val result = HermesDevicePairClient(shortClient)
            .pairDevice(baseUrl(), CANONICAL_CODE)

        assertEquals(DevicePairResult.Failure(DevicePairError.Timeout), result)
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.pairDevice(unavailable, CANONICAL_CODE)

        assertTrue(
            "expected a closed transport failure, got $result",
            result is DevicePairResult.Failure
        )
        assertFalse(result.toString().contains(CANONICAL_CODE))
    }

    @Test
    fun localCodeValidationFailuresSendNoRequest() {
        listOf(
            "http://example.invalid" to "http-url",
            "not-a-url" to "bad-url",
            baseUrl() to "blank-code",
            baseUrl() to "short-code",
            baseUrl() to "long-code",
            baseUrl() to "forbidden-letter-code",
            baseUrl() to "zero-digit-code",
            baseUrl() to "symbol-code"
        ).forEach { (url, label) ->
            val code = when (label) {
                "blank-code" -> "   "
                "short-code" -> CANONICAL_CODE.dropLast(1)
                "long-code" -> CANONICAL_CODE + "3"
                "forbidden-letter-code" -> "I" + CANONICAL_CODE.substring(1)
                "zero-digit-code" -> CANONICAL_CODE.replaceFirst("1", "0")
                "symbol-code" -> CANONICAL_CODE.substring(0, 3) + "!" + CANONICAL_CODE.substring(4)
                else -> CANONICAL_CODE
            }
            val result = client.pairDevice(url, code)
            assertEquals(
                "$label must fail closed locally",
                DevicePairResult.Failure(DevicePairError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(HermesDevicePairClient.CREATED_CODE)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun credentialBody(): String =
        "{\"object\":\"hermes.device.credential\",\"device_id\":\"$DEVICE_ID\"," +
            "\"device_key\":\"$DEVICE_KEY\",\"profile\":\"fixture-profile\"," +
            "\"label\":\"fixture-label\",\"permissions\":[\"approvals\",\"chat\",\"runs\"]," +
            "\"issued_at\":1757000000.125}"

    private fun errorBody(code: String): String =
        "{\"error\":{\"message\":\"dynamic server detail\",\"type\":\"error\",\"code\":\"$code\"}}"

    private companion object {
        const val DEVICE_ID = "3f9c2a1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        const val DEVICE_KEY = "hd_0123456789012345678901234567890123456789012"
        // 22 valid letters + 2 digits = 24.
        const val CANONICAL_CODE = "ABCDEFGHJKMNPQRSTVWXYZ12"
        const val DASHED_CODE = "ABCDEFG-HJKMN-PQRST-VWXYZ-12   "
    }
}
