package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalRoute
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalTags
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import androidx.webkit.WebViewFeature
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal screen with a fake source: the unlock gate comes before any
 * connection, every state names what happened, and the real xterm.js page
 * talks to the app (it reports its size and the key row's input).
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeTerminalSource(
        @Volatile var availability: TerminalAvailability = TerminalAvailability.Available
    ) : TerminalSource {
        @Volatile var opens = 0
        @Volatile var availabilityChecks = 0
        @Volatile var listener: ((TerminalEvent) -> Unit)? = null
        @Volatile var closed = false
        val sent = CopyOnWriteArrayList<String>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()

        override fun availability(profileId: String): TerminalAvailability {
            availabilityChecks++
            return availability
        }

        override fun open(profileId: String, onEvent: (TerminalEvent) -> Unit): TerminalConnection {
            opens++
            listener = onEvent
            return object : TerminalConnection {
                override fun send(bytes: ByteArray): Boolean = sent.add(bytes.toString(Charsets.UTF_8))
                override fun resize(columns: Int, rows: Int): Boolean = sizes.add(columns to rows)
                override fun close() {
                    closed = true
                }
            }
        }
    }

    private val webViewSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private fun show(source: TerminalSource, authenticator: FakeAppAuthenticator?) {
        composeRule.setContent {
            TerminalRoute(profileId = "p1", source = source, authenticator = authenticator, onBack = {})
        }
    }

    private fun text(id: Int) = context.getString(id)

    @Test
    fun anOldWebViewIsNamedBeforeAnythingOpens() {
        assumeFalse("this device's WebView supports the terminal", webViewSupported)
        val source = FakeTerminalSource()
        val auth = FakeAppAuthenticator(autoResult = true)
        show(source, auth)
        composeRule.onNodeWithText(text(R.string.terminal_webview_unsupported)).assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(0, source.availabilityChecks)
        assertEquals(0, auth.callCount)
        assertEquals(0, source.opens)
    }

    @Test
    fun aServerWithoutTerminalSaysSoAndNeverAsksForTheCredential() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource(TerminalAvailability.Unsupported)
        val auth = FakeAppAuthenticator(autoResult = true)
        show(source, auth)
        composeRule.onNodeWithText(text(R.string.terminal_unsupported_title)).assertIsDisplayed()
        assertEquals(0, auth.callCount)
        assertEquals(0, source.opens)
    }

    @Test
    fun aRefusedUnlockOpensNothingAndCanBeRepeated() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        val auth = FakeAppAuthenticator(autoResult = false)
        show(source, auth)
        composeRule.onNodeWithText(text(R.string.terminal_unlock_failed)).assertIsDisplayed()
        assertEquals(0, source.opens)
        composeRule.onNodeWithTag(TerminalTags.UNLOCK).performClick()
        composeRule.waitUntil(5_000) { auth.callCount == 2 }
        assertEquals(0, source.opens)
    }

    @Test
    fun withoutAScreenLockNoTerminalOpens() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        show(source, FakeAppAuthenticator(available = false))
        composeRule.onNodeWithText(text(R.string.terminal_unlock_unavailable)).assertIsDisplayed()
        assertEquals(0, source.opens)
    }

    @Test
    fun anUnreachableServerOffersAReconnect() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource(TerminalAvailability.Failed)
        show(source, FakeAppAuthenticator(autoResult = true))
        composeRule.onNodeWithText(text(R.string.terminal_check_failed)).assertIsDisplayed()
        source.availability = TerminalAvailability.Unsupported
        composeRule.onNodeWithTag(TerminalTags.RECONNECT).performClick()
        composeRule.onNodeWithText(text(R.string.terminal_unsupported_title)).assertIsDisplayed()
    }

    @Test
    fun afterTheUnlockThePageReportsItsSizeAndSendsTheKeyRowsInput() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        val auth = FakeAppAuthenticator(autoResult = true)
        show(source, auth)
        composeRule.onNodeWithTag(TerminalTags.VIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalTags.KEYS).assertIsDisplayed()
        assertEquals(1, auth.callCount)
        composeRule.waitUntil(5_000) { source.opens == 1 }
        // The xterm.js page loaded from the app's assets and measured its
        // final area (an early layout pass may report less first).
        composeRule.waitUntil(15_000) { source.sizes.any { (columns, rows) -> columns >= 20 && rows >= 5 } }
        val (columns, rows) = source.sizes.last()
        assertTrue("size $columns x $rows (all: ${source.sizes})", columns in 20..500 && rows in 5..300)
        composeRule.onNodeWithTag("terminal_key_pipe").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { "|" in source.sent }
        composeRule.onNodeWithTag("terminal_key_esc").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { "\u001b" in source.sent }
        // Outside full-screen programs the arrows use the normal cursor mode.
        composeRule.onNodeWithTag("terminal_key_up").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { "\u001b[A" in source.sent }
    }

    @Test
    fun aClosedTerminalNamesTheReasonAndReconnectAsksAgain() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        val auth = FakeAppAuthenticator(autoResult = true)
        show(source, auth)
        composeRule.waitUntil(5_000) { source.listener != null }
        source.listener!!.invoke(TerminalEvent.Closed(TerminalCloseReason.Exited))
        composeRule.onNodeWithText(text(R.string.terminal_closed_exited)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.terminal_session_kept)).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalTags.RECONNECT).performClick()
        composeRule.waitUntil(5_000) { source.opens == 2 }
        assertEquals("a reconnect asks for the credential again", 2, auth.callCount)
    }

    @Test
    fun aRevokedDeviceIsNamed() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        show(source, FakeAppAuthenticator(autoResult = true))
        composeRule.waitUntil(5_000) { source.listener != null }
        source.listener!!.invoke(TerminalEvent.Closed(TerminalCloseReason.Unauthorized))
        composeRule.onNodeWithText(text(R.string.terminal_closed_unauthorized)).assertIsDisplayed()
    }

    @Test
    fun leavingTheScreenClosesTheConnection() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            if (visible.value) {
                TerminalRoute(
                    profileId = "p1", source = source,
                    authenticator = FakeAppAuthenticator(autoResult = true), onBack = {}
                )
            }
        }
        composeRule.waitUntil(5_000) { source.opens == 1 }
        composeRule.waitUntil(5_000) { source.listener != null }
        Thread.sleep(500)
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitUntil(5_000) { source.closed }
    }
}
