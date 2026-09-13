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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mission 017B: the authenticated capability probe must negotiate the
 * EXACT `device_credentials` contract of the authoritative server
 * contract:
 *
 *   features.device_credentials =
 *     {enabled: true, protocol_version: 1,
 *      pair_endpoint: "device_pair",
 *      self_revoke_endpoint: "device_self_revoke"}
 *   endpoints.device_pair         = {method: "POST", path: "/v1/devices/pair"}
 *   endpoints.device_self_revoke  = {method: "POST", path: "/v1/devices/self/revoke"}
 *
 * Any deviation (missing block, wrong value, extra key, wrong method or
 * path) must fail CLOSED with `deviceCredentialsSupported = false` while
 * the rest of the connection stays usable.
 */
class DeviceCredentialCapabilityTest {

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
    fun exactDeviceCredentialsContractIsRecognised() {
        enqueueCapabilities(capabilitiesJson().withDeviceCredentials())

        val result = success(client)

        assertTrue(result.deviceCredentialsSupported)
    }

    @Test
    fun missingDeviceCredentialsBlockStaysClosed() {
        enqueueCapabilities(capabilitiesJson())

        val result = success(client)

        assertFalse(result.deviceCredentialsSupported)
    }

    @Test
    fun deviatingDeviceCredentialsContractsStayClosed() {
        // Each deviation is a single, closed contract break.
        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("enabled", false)
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("protocol_version", 2)
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("pair_endpoint", "device_pair_v2")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("self_revoke_endpoint", "self_revoke")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("extra", true)
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateEndpoints { endpoints ->
            endpoints.getJSONObject("device_pair").put("path", "/v1/devices/pairing")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateEndpoints { endpoints ->
            endpoints.getJSONObject("device_self_revoke").put("method", "DELETE")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateEndpoints { endpoints ->
            endpoints.getJSONObject("device_pair").put("extra", "POST")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateEndpoints { endpoints ->
            endpoints.remove("device_self_revoke")
        })
        assertClosed()

        enqueueCapabilities(capabilitiesJson().withDeviceCredentials().mutateContract { contract ->
            contract.put("pair_endpoint", 1)
        })
        assertClosed()
    }

    private fun success(client: HermesConnectionClient): HermesConnectionResult.Success {
        val result = client.probe(draft())
        assertTrue("probe must stay successful for the other contract parts", result is HermesConnectionResult.Success)
        return result as HermesConnectionResult.Success
    }

    private fun assertClosed() {
        val result = client.probe(draft())
        when (result) {
            is HermesConnectionResult.Success ->
                assertFalse("the contract deviation must fail closed", result.deviceCredentialsSupported)
            is HermesConnectionResult.Failure ->
                org.junit.Assert.fail("the probe must not fail on a closed device-credential capability: ${result.reason}")
        }
    }

    private fun enqueueCapabilities(capabilities: JSONObject) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    JSONObject()
                        .put("status", "ok")
                        .put("platform", "hermes-agent")
                        .put("version", "0.20-test")
                        .toString()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(capabilities.toString())
        )
    }

    private fun draft() = ProfileDraft(
        profileName = "Fixture",
        baseUrl = server.url("/").toString(),
        apiKey = SecretValue(FIXTURE_KEY)
    )

    private fun capabilitiesJson() = JSONObject()
        .put("object", "hermes.api_server.capabilities")
        .put("platform", "hermes-agent")
        .put(
            "auth",
            JSONObject().put("type", "bearer").put("required", true)
        )
        .put(
            "runtime",
            JSONObject().put("mode", "server_agent").put("tool_execution", "server")
        )
        .put(
            "features",
            JSONObject()
                .put("session_resources", true)
                .put("session_chat_streaming", true)
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
        )

    private fun JSONObject.withDeviceCredentials(): JSONObject {
        put(
            "features",
            getJSONObject("features").put(
                "device_credentials",
                JSONObject()
                    .put("enabled", true)
                    .put("protocol_version", 1)
                    .put("pair_endpoint", "device_pair")
                    .put("self_revoke_endpoint", "device_self_revoke")
            )
        )
        put(
            "endpoints",
            getJSONObject("endpoints")
                .put(
                    "device_pair",
                    JSONObject().put("method", "POST").put("path", "/v1/devices/pair")
                )
                .put(
                    "device_self_revoke",
                    JSONObject().put("method", "POST").put("path", "/v1/devices/self/revoke")
                )
        )
        return this
    }

    private fun JSONObject.mutateContract(mutation: (JSONObject) -> Unit): JSONObject {
        val features = getJSONObject("features")
        val contract = features.getJSONObject("device_credentials")
        mutation(contract)
        put("features", features)
        return this
    }

    private fun JSONObject.mutateEndpoints(mutation: (JSONObject) -> Unit): JSONObject {
        val endpoints = getJSONObject("endpoints")
        mutation(endpoints)
        put("endpoints", endpoints)
        return this
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
