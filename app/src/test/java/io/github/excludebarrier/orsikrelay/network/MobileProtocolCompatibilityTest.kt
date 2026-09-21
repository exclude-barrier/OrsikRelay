package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 mobile-protocol compatibility contract (explicit versioning).
 *
 * Proves the authenticated capability probe negotiates the mobile-protocol
 * version explicitly and fail-closed, for BOTH soft mobile capabilities
 * (session-chat interactive approvals and device credentials):
 *
 * - an old, fully-specified protocol-v1 advertisement is accepted (open);
 * - an unknown or future protocol version (0, 2, 3, large) stays closed;
 * - a missing or non-integer protocol version stays closed;
 * - the two capabilities negotiate their version independently;
 * - a malformed capabilities document fails the whole probe closed
 *   (`INCOMPATIBLE_API`) instead of being half-accepted.
 *
 * No secret, URL, key or server detail may appear in any failure result.
 */
class MobileProtocolCompatibilityTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesConnectionClient

    @Before
    fun setUp() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesConnectionClient(
            OkHttpClient.Builder()
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager
                )
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun protocolVersionOneIsSupportedForBothCapabilities() {
        val result = probe(baseCapabilities())

        result as HermesConnectionResult.Success
        assertTrue(result.sessionChatInteractiveApprovals)
        assertTrue(result.deviceCredentialsSupported)
    }

    @Test
    fun unknownOrFutureProtocolVersionsStayClosedForBothCapabilities() {
        listOf<Any>(0, 2, 3, 100).forEach { version ->
            val result = probe(baseCapabilities().withVersions(version, version))

            result as HermesConnectionResult.Success
            assertFalse(
                "approvals must stay closed for version $version",
                result.sessionChatInteractiveApprovals
            )
            assertFalse(
                "device credentials must stay closed for version $version",
                result.deviceCredentialsSupported
            )
        }
    }

    @Test
    fun missingProtocolVersionStaysClosedForBothCapabilities() {
        val capabilities = baseCapabilities()
        capabilities.getJSONObject("features")
            .getJSONObject("session_chat_interactive_approvals")
            .remove("protocol_version")
        capabilities.getJSONObject("features")
            .getJSONObject("device_credentials")
            .remove("protocol_version")

        val result = probe(capabilities)

        result as HermesConnectionResult.Success
        assertFalse(result.sessionChatInteractiveApprovals)
        assertFalse(result.deviceCredentialsSupported)
    }

    @Test
    fun nonIntegerProtocolVersionStaysClosedForBothCapabilities() {
        // `1.5` stays fractional on the wire (org.json would normalise `1.0`
        // to the integer `1`, which is legitimately v1). Each entry is a
        // distinct non-integer wire shape and must stay closed.
        listOf<Any>("1", 1.5, JSONArray().put(1)).forEach { version ->
            val result = probe(baseCapabilities().withVersions(version, version))

            result as HermesConnectionResult.Success
            assertFalse(
                "approvals must stay closed for non-integer $version",
                result.sessionChatInteractiveApprovals
            )
            assertFalse(
                "device credentials must stay closed for non-integer $version",
                result.deviceCredentialsSupported
            )
        }
    }

    @Test
    fun eachCapabilityNegotiatesItsOwnProtocolVersionIndependently() {
        // Approvals advertise a future version, device credentials stay at v1:
        // only the future-version capability is closed.
        val futureApprovals = probe(baseCapabilities().withVersions(2, 1))
        futureApprovals as HermesConnectionResult.Success
        assertFalse(futureApprovals.sessionChatInteractiveApprovals)
        assertTrue(futureApprovals.deviceCredentialsSupported)

        // Device credentials advertise a future version, approvals stay at v1.
        val futureDevice = probe(baseCapabilities().withVersions(1, 2))
        futureDevice as HermesConnectionResult.Success
        assertTrue(futureDevice.sessionChatInteractiveApprovals)
        assertFalse(futureDevice.deviceCredentialsSupported)
    }

    @Test
    fun malformedCapabilitiesFailTheWholeProbeClosed() {
        // Body that is not JSON.
        server.enqueue(healthResponse())
        server.enqueue(jsonResponse("not-json"))
        assertIncompatible()

        // Wrong object discriminator.
        enqueueCapabilities(
            baseCapabilities().apply { put("object", "hermes.other.capabilities") }
        )
        assertIncompatible()

        // Wrong platform.
        enqueueCapabilities(
            baseCapabilities().apply { put("platform", "different-platform") }
        )
        assertIncompatible()

        // Missing a required top-level section.
        enqueueCapabilities(baseCapabilities().apply { remove("endpoints") })
        assertIncompatible()
    }

    private fun probe(capabilities: JSONObject): HermesConnectionResult {
        enqueueCapabilities(capabilities)
        return client.probe(draft())
    }

    private fun assertIncompatible() {
        val result = client.probe(draft())
        assertTrue("probe must fail closed", result is HermesConnectionResult.Failure)
        result as HermesConnectionResult.Failure
        assertEquals(ConnectionFailureReason.INCOMPATIBLE_API, result.reason)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun enqueueCapabilities(capabilities: JSONObject) {
        server.enqueue(healthResponse())
        server.enqueue(jsonResponse(capabilities.toString()))
    }

    private fun healthResponse() = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(
            JSONObject()
                .put("status", "ok")
                .put("platform", "hermes-agent")
                .put("version", "0.20-test")
                .toString()
        )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun draft() = ProfileDraft(
        profileName = "Fixture",
        baseUrl = server.url("/").toString(),
        apiKey = SecretValue(FIXTURE_KEY)
    )

    private fun baseCapabilities() = JSONObject()
        .put("object", "hermes.api_server.capabilities")
        .put("platform", "hermes-agent")
        .put("auth", JSONObject().put("type", "bearer").put("required", true))
        .put(
            "runtime",
            JSONObject().put("mode", "server_agent").put("tool_execution", "server")
        )
        .put(
            "features",
            JSONObject()
                .put("session_resources", true)
                .put("session_chat_streaming", true)
                .put("run_approval_response", true)
                .put(
                    "session_chat_interactive_approvals",
                    JSONObject()
                        .put("enabled", true)
                        .put("protocol_version", 1)
                        .put("event", "approval.request")
                        .put("choices", JSONArray().put("once").put("deny"))
                        .put("response_endpoint", "run_approval")
                )
                .put(
                    "device_credentials",
                    JSONObject()
                        .put("enabled", true)
                        .put("protocol_version", 1)
                        .put("pair_endpoint", "device_pair")
                        .put("self_revoke_endpoint", "device_self_revoke")
                )
        )
        .put(
            "endpoints",
            JSONObject()
                .put("health", JSONObject().put("method", "GET").put("path", "/health"))
                .put(
                    "sessions",
                    JSONObject().put("method", "GET").put("path", "/api/sessions")
                )
                .put(
                    "session_chat_stream",
                    JSONObject()
                        .put("method", "POST")
                        .put("path", "/api/sessions/{session_id}/chat/stream")
                )
                .put(
                    "run_approval",
                    JSONObject().put("method", "POST").put("path", "/v1/runs/{run_id}/approval")
                )
                .put(
                    "device_pair",
                    JSONObject().put("method", "POST").put("path", "/v1/devices/pair")
                )
                .put(
                    "device_self_revoke",
                    JSONObject().put("method", "POST").put("path", "/v1/devices/self/revoke")
                )
        )

    private fun JSONObject.withVersions(approvalsVersion: Any, deviceVersion: Any): JSONObject {
        val features = getJSONObject("features")
        features.getJSONObject("session_chat_interactive_approvals")
            .put("protocol_version", approvalsVersion)
        features.getJSONObject("device_credentials")
            .put("protocol_version", deviceVersion)
        put("features", features)
        return this
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
