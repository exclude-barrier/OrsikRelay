package io.github.excludebarrier.orsikrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P13 real Android end-to-end: the app's own networking clients talk to a live
 * Orsik Mobile Bridge (which fronts the unmodified official Hermes core) over
 * HTTPS, on the emulator.
 *
 * Requires instrumentation arguments:
 *   liveBaseUrl    e.g. https://<bridge-host>.<tailnet>.ts.net:8443
 *   livePairingCode  a fresh 24-character single-use code
 *
 * Run (explicitly, against the live stack):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeE2ETest \
 *     -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=... \
 *     -Pandroid.testInstrumentationRunnerArguments.livePairingCode=...
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeE2ETest {

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    @Test
    fun pairsProbesAndNegotiatesAgainstLiveBridge() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)

        // 1. Real pairing over HTTPS using the shipped client (no mock server).
        val paired = HermesDevicePairClient().pairDevice(baseUrl!!, pairingCode!!)
        assertTrue("expected DevicePairResult.Paired, got $paired", paired is DevicePairResult.Paired)
        val credential = (paired as DevicePairResult.Paired).credential
        assertTrue(
            "permissions must be a superset of approvals/chat/runs",
            credential.permissions.containsAll(listOf("approvals", "chat", "runs"))
        )

        // 2. Authenticated health + capability negotiation with the issued device key.
        val deviceKey = credential.deviceKey.useRaw { it }
        val probe = HermesConnectionClient().probe(
            ProfileDraft(profileName = "live-bridge", baseUrl = baseUrl, apiKey = SecretValue(deviceKey))
        )
        assertTrue("expected HermesConnectionResult.Success, got $probe", probe is HermesConnectionResult.Success)
        val success = probe as HermesConnectionResult.Success
        assertTrue("device_credentials must be negotiated", success.deviceCredentialsSupported)
        assertTrue("session_chat_interactive_approvals must be negotiated", success.sessionChatInteractiveApprovals)
    }
}
