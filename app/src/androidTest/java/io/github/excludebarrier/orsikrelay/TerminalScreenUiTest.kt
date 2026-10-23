package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.files.FilesAskResult
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalFontStore
import io.github.excludebarrier.orsikrelay.ui.terminal.TerminalHermes
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    /** Unlocks, opens and waits until the page reported a real size. */
    private fun connected(source: FakeTerminalSource, hermes: TerminalHermes? = null) {
        composeRule.setContent {
            TerminalRoute(
                profileId = "p1", source = source, authenticator = FakeAppAuthenticator(autoResult = true),
                onBack = {}, serverLabel = "testhost", hermes = hermes, fontStore = MemoryFontStore()
            )
        }
        composeRule.waitUntil(5_000) { source.listener != null }
        source.listener!!.invoke(TerminalEvent.Opened)
        composeRule.waitUntil(15_000) { source.sizes.any { (columns, rows) -> columns >= 20 && rows >= 5 } }
    }

    private fun key(name: String) = composeRule.onNodeWithTag("terminal_key_$name").performClick()

    private fun awaitSent(expected: String) = composeRule.waitUntil(5_000) { expected in source().sent }

    private var current: FakeTerminalSource? = null
    private fun source() = current!!

    private class MemoryFontStore(var size: Int = 12) : TerminalFontStore {
        override fun fontSize() = size
        override fun setFontSize(size: Int) {
            this.size = size
        }
    }

    @Test
    fun theConnectingCardShowsUntilTheSocketIsOpen() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        composeRule.setContent {
            TerminalRoute(profileId = "p1", source = source, authenticator = FakeAppAuthenticator(autoResult = true), onBack = {}, serverLabel = "testhost")
        }
        composeRule.waitUntil(5_000) { source.listener != null }
        composeRule.onNodeWithTag(TerminalTags.CONNECTING).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.terminal_subtitle_connecting, "testhost")).assertIsDisplayed()
        source.listener!!.invoke(TerminalEvent.Opened)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(TerminalTags.CONNECTING).fetchSemanticsNodes().isEmpty() }
        composeRule.waitUntil(15_000) { source.sizes.any { it.second >= 5 } }
        val (columns, rows) = source.sizes.last()
        // The subtitle names host, tmux and the size the page reported to the PTY.
        composeRule.onNodeWithText(context.getString(R.string.terminal_subtitle_connected, "testhost", "${columns}×$rows")).assertIsDisplayed()
    }

    @Test
    fun arrowsEnterAndSymbolsSendTheirSequences() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        connected(source)
        key("up"); awaitSent("\u001b[A")
        key("left"); awaitSent("\u001b[D")
        key("enter"); awaitSent("\r")
        key("esc"); awaitSent("\u001b")
        key("tab"); awaitSent("\t")
        key("dollar"); awaitSent("$")
        key("underscore"); awaitSent("_")
    }

    @Test
    fun ctrlAndAltLatchForTheNextKeyOnly() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        connected(source)
        key("ctrl")
        composeRule.onNodeWithTag(TerminalTags.MODIFIER_HINT).assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_key_ctrl").assertIsSelected()
        key("underscore")
        awaitSent("\u001f")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(TerminalTags.MODIFIER_HINT).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("terminal_key_ctrl").assertIsNotSelected()
        key("alt")
        key("slash")
        awaitSent("\u001b/")
        key("ctrl")
        key("up")
        awaitSent("\u001b[1;5A")
        // Tapping a latched modifier again releases it.
        key("alt")
        key("alt")
        key("pipe")
        awaitSent("|")
    }

    @Test
    fun aLostConnectionKeepsTheConsoleAndOffersAReconnect() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        val auth = FakeAppAuthenticator(autoResult = true)
        composeRule.setContent {
            TerminalRoute(profileId = "p1", source = source, authenticator = auth, onBack = {}, serverLabel = "testhost", fontStore = MemoryFontStore())
        }
        composeRule.waitUntil(5_000) { source.listener != null }
        source.listener!!.invoke(TerminalEvent.Opened)
        source.listener!!.invoke(TerminalEvent.Closed(TerminalCloseReason.Disconnected))
        composeRule.onNodeWithTag(TerminalTags.DISCONNECTED).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalTags.VIEW).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.terminal_subtitle_closed, "testhost")).assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_key_esc").assertHasNoClickAction()
        composeRule.onNodeWithTag(TerminalTags.RECONNECT).performClick()
        composeRule.waitUntil(5_000) { source.opens == 2 }
        assertEquals("a reconnect asks for the credential again", 2, auth.callCount)
        composeRule.onNodeWithTag(TerminalTags.CONNECTING).assertIsDisplayed()
    }

    @Test
    fun theLastOutputGoesToHermesAsContext() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        val asked = CopyOnWriteArrayList<Pair<String, String>>()
        connected(source, hermes = TerminalHermes { output, question -> asked += output to question; FilesAskResult.Opened })
        source.listener!!.invoke(TerminalEvent.Output("disk 88% voll\r\n".toByteArray()))
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.onNodeWithTag(TerminalTags.LAST_OUTPUT).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(TerminalTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(context.getString(R.string.terminal_output_badge)).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalTags.COMPOSER).performTextInput("Warum?")
        composeRule.onNodeWithTag(io.github.excludebarrier.orsikrelay.ui.components.ComposerDockTags.SEND).performClick()
        composeRule.waitUntil(5_000) { asked.isNotEmpty() }
        assertTrue(asked.single().first, asked.single().first.contains("disk 88% voll"))
        assertEquals("Warum?", asked.single().second)
    }

    @Test
    fun theFontSizeIsKept() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        val store = MemoryFontStore(12)
        composeRule.setContent {
            TerminalRoute(profileId = "p1", source = source, authenticator = FakeAppAuthenticator(autoResult = true), onBack = {}, fontStore = store)
        }
        composeRule.waitUntil(5_000) { source.listener != null }
        source.listener!!.invoke(TerminalEvent.Opened)
        composeRule.onNodeWithTag(TerminalTags.FONT).performClick()
        composeRule.onNodeWithTag(TerminalTags.FONT_LARGER).performClick()
        composeRule.runOnIdle { assertEquals(13, store.size) }
    }

    @Test
    fun tmuxSessionsAreAPreviewOnly() {
        assumeTrue("WebView too old for the terminal", webViewSupported)
        val source = FakeTerminalSource().also { current = it }
        connected(source)
        composeRule.onNodeWithTag(TerminalTags.TABS).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalTags.MORE).performClick()
        composeRule.onNodeWithTag(TerminalTags.SESSIONS).assertIsNotEnabled()
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
