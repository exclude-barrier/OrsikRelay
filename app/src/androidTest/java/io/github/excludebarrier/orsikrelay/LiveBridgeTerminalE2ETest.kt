package io.github.excludebarrier.orsikrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairResult
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.network.HermesTerminalClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalRoute
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's terminal client against a live bridge with BRIDGE_TERMINAL=1
 * over Tailscale Serve: capability, a real shell in tmux, resize.
 * Run like the other [LiveBridge] tests (liveBaseUrl / livePairingCode).
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeTerminalE2ETest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    @Test
    fun opensARealShellOverTheTailnet() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)
        val paired = io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient().pairDevice(baseUrl!!, pairingCode!!)
        assertTrue("expected Paired, got $paired", paired is DevicePairResult.Paired)
        val key = SecretValue((paired as DevicePairResult.Paired).credential.deviceKey.useRaw { it })
        val client = HermesTerminalClient()
        assertTrue("the live bridge must advertise the terminal", client.supports(baseUrl, key))

        val output = ByteArrayOutputStream()
        val marker = CountDownLatch(1)
        val connection = client.open(baseUrl, key) { event ->
            if (event is TerminalEvent.Output) {
                synchronized(output) {
                    output.write(event.bytes)
                    if (output.toString(Charsets.UTF_8.name()).contains("ORSIK_TERM_42")) marker.countDown()
                }
            }
        }
        assertNotNull(connection)
        assertTrue(connection!!.resize(90, 25))
        assertTrue(connection.send("echo ORSIK_TERM_\$((6*7)); stty size\r".toByteArray()))
        val seen = marker.await(20, TimeUnit.SECONDS)
        Thread.sleep(500)
        val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
        connection.close()
        assertTrue("shell output: ${text.takeLast(200)}", seen)
        // tmux keeps one row for its status line.
        assertTrue("size: ${text.takeLast(200)}", text.contains("24 90"))
    }

    /**
     * The whole screen against the live shell: the xterm.js page's size
     * reaches the server PTY (tmux reports it back) and the output flows
     * through the screen.
     */
    @Test
    fun theScreenDrivesARealShell() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)
        val paired = io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient().pairDevice(baseUrl!!, pairingCode!!)
        assertTrue("expected Paired, got $paired", paired is DevicePairResult.Paired)
        val key = SecretValue((paired as DevicePairResult.Paired).credential.deviceKey.useRaw { it })
        val client = HermesTerminalClient()
        val output = StringBuffer()
        val sizes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
        var live: TerminalConnection? = null
        val source = object : TerminalSource {
            override fun availability(profileId: String) =
                if (client.supports(baseUrl, key)) TerminalAvailability.Available else TerminalAvailability.Unsupported

            override fun open(profileId: String, onEvent: (TerminalEvent) -> Unit): TerminalConnection? {
                val real = client.open(baseUrl, key) { event ->
                    if (event is TerminalEvent.Output) output.append(event.bytes.toString(Charsets.UTF_8))
                    onEvent(event)
                } ?: return null
                return object : TerminalConnection by real {
                    override fun resize(columns: Int, rows: Int): Boolean {
                        sizes.add(columns to rows)
                        return real.resize(columns, rows)
                    }
                }.also { live = it }
            }
        }
        composeRule.setContent {
            TerminalRoute(profileId = "live", source = source, authenticator = FakeAppAuthenticator(autoResult = true), onBack = {})
        }
        composeRule.waitUntil(30_000) { live != null && sizes.any { it.second >= 5 } }
        settle(1_500)
        val (columns, rows) = sizes.last()
        live!!.send("clear; echo SCREEN_\$((6*7)); stty size; ls -la --color=always ~ | head -5\r".toByteArray())
        composeRule.waitUntil(20_000) { output.contains("SCREEN_42") }
        settle(1_500)
        // tmux keeps one row for its status line.
        assertTrue("page ${columns}x$rows, output: ${output.takeLast(300)}", output.contains("${rows - 1} $columns"))
        // Room for a screenshot of the rendered terminal.
        settle(5_000)
    }

    /** Lets frames run (a plain sleep would freeze the composition). */
    private fun settle(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
    }
}
