package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * IME / insets behavior of the REAL chat layout: the production
 * [HermesAppSurface] root (edge-to-edge, `safeDrawingPadding()`) hosts the
 * production [ChatScreen] with fixture history. Verifies the resize flow
 * for the keyboard:
 *
 * - the header stays put (no root panning);
 * - the message-list region keeps a positive height and shrinks by exactly
 *   the IME offset (no double nav-bar/IME padding);
 * - the composer sits directly above the keyboard region;
 * - closing the IME restores the original layout.
 *
 * The IME insets are injected deterministically via
 * [ViewCompat.dispatchApplyWindowInsets]; the real-emulator keyboard is
 * additionally exercised for the visual screenshot states.
 */
class ChatImeLayoutUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Deterministic fake keyboard height (px). The status- and
    // navigation-bar insets are read from the real window: the window
    // keeps its own insets source control for those types, so only the
    // IME inset is injectable.
    private val imeBottom = 600

    private fun realNavBottom(): Int =
        ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

    @Test
    fun imeOpenResizesChatWithoutPanningOrDoubleInsets() {
        setScreen()
        waitForText("Kurze Antwort")
        val navBottom = realNavBottom()
        // State A: no IME.
        val headerTopA = boundsTop("Chat")
        val composerTopA = boundsTop("Nachricht")
        val listTopA = listTop()
        val listHeightA = composerTopA - listTopA
        assertTrue("message list must have positive height without IME", listHeightA > 0)

        // State B: IME open (fake insets: keyboard + nav bar).
        dispatchImeInsets(imeBottom)
        val headerTopB = boundsTop("Chat")
        val composerTopB = boundsTop("Nachricht")
        val listTopB = listTop()
        val listHeightB = composerTopB - listTopB
        val rootHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height.toInt()

        // 1. No root panning: the header does not move at all.
        assertEquals(
            "header must not pan when the IME opens",
            headerTopA,
            headerTopB
        )
        // 2. Exact resize: the column shrinks by exactly the IME offset
        //    (ime - nav), because the root surface already applies the nav
        //    bar inset. A double-counted nav bar would add another
        //    `navBottom` here.
        assertEquals(
            "composer must move up by exactly the IME offset (no double insets)",
            composerTopA - (imeBottom - navBottom),
            composerTopB
        )
        // 3. The message list keeps a positive height and shrinks by the
        //    same offset.
        assertTrue("message list must keep positive height with IME", listHeightB > 0)
        assertEquals(
            "message list must shrink by exactly the IME offset",
            listHeightA,
            listHeightB + (imeBottom - navBottom)
        )
        // 4. No double bottom inset: the composer bottom stays within its
        //    12dp bottom padding above the keyboard top edge.
        val composerBottomB = boundsBottom("Nachricht")
        val keyboardTop = rootHeight - (navBottom + imeBottom)
        assertTrue(
            "composer bottom $composerBottomB must sit directly above keyboard top $keyboardTop (no double inset)",
            keyboardTop - composerBottomB <= 64
        )

        // State C: IME closed again — original layout restored.
        dispatchImeInsets(0)
        assertEquals("header must return after the IME closes", headerTopA, boundsTop("Chat"))
        assertEquals("composer must return after the IME closes", composerTopA, boundsTop("Nachricht"))
    }

    @Test
    fun realKeyboardOpenKeepsHeaderAndComposerVisible() {
        setScreen()
        waitForText("Kurze Antwort")
        val headerTopBefore = boundsTop("Chat")
        val composerTopBefore = boundsTop("Nachricht")

        val imeOpened = openRealKeyboard()
        if (imeOpened) awaitIme(open = true)
        composeRule.waitForIdle()

        val headerTopAfter = boundsTop("Chat")
        assertEquals("header must not pan when the real keyboard opens", headerTopBefore, headerTopAfter)
        if (imeOpened) {
            assertTrue(
                "real IME must actually open: composer must move up by a substantial inset, but it stayed at $composerTopBefore",
                boundsTop("Nachricht") < composerTopBefore - 500
            )
        }
        // The real IME changes the window insets in several frames; the
        // semantics tree can still report the previous clipped state for a
        // moment after `waitForIdle()` (measured: the identical assertion
        // passes as soon as one further idle round-trip happens, while the
        // unclipped header bounds are unchanged and the composer already sits
        // exactly on the IME edge). The invariant is unchanged and still
        // enforced - each node MUST become displayed; only the settle window
        // is tolerated instead of failing on a stale semantics snapshot.
        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Nachricht").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
    }

    @CaptureOnly
    @Test
    fun imeScreenshotsForVisualVerification() {
        setScreen()
        waitForText("Kurze Antwort")
        shoot("01_kb_closed_light")
        Thread.sleep(2_500)

        dispatchImeInsets(imeBottom)
        shoot("02_ime_open_layout_light")
        Thread.sleep(2_500)

        // Multi-line composer while the IME region is reserved. Focusing
        // the field makes the window re-dispatch its real insets (IME
        // closed here), so the fake IME inset is re-applied afterwards.
        composeRule.onNodeWithText("Nachricht").performTextInput("Zweite Zeile\nDritte Zeile")
        composeRule.waitForIdle()
        dispatchImeInsets(imeBottom)
        Thread.sleep(2_000)
        shoot("03_multiline_ime_light")
        Thread.sleep(2_000)

        dispatchImeInsets(0)
        composeRule.waitForIdle()
        shoot("04_kb_closed_after_close")
        Thread.sleep(1_000)
    }

    @CaptureOnly
    @Test
    fun realKeyboardScreenshotLight() {
        setScreen(dark = false)
        waitForText("Kurze Antwort")
        openRealKeyboard()
        Thread.sleep(3_000)
        shoot("05_real_ime_light")
        Thread.sleep(1_000)
    }

    @CaptureOnly
    @Test
    fun realKeyboardScreenshotDark() {
        setScreen(dark = true)
        waitForText("Kurze Antwort")
        openRealKeyboard()
        Thread.sleep(3_000)
        shoot("06_real_ime_dark")
        Thread.sleep(1_000)
    }

    @Test
    fun realKeyboardCloseThenReopenIsStable() {
        setScreen()
        waitForText("Kurze Antwort")
        val headerTopClosed = boundsTop("Chat")
        val composerTopClosed = boundsTop("Nachricht")

        // State A: real keyboard open.
        val imeOpenedA = openRealKeyboard()
        if (imeOpenedA) awaitIme(open = true)
        assertEquals(
            "header must not pan while the real IME is open",
            headerTopClosed,
            boundsTop("Chat")
        )
        if (imeOpenedA) {
            assertTrue(
                "real IME must actually open in state A: composer must move up",
                boundsTop("Nachricht") < composerTopClosed - 500
            )
        }
        shoot("07_real_ime_open")
        Thread.sleep(1_500) // host-side screencap window before the IME closes

        // State B: close the IME programmatically from the app process.
        // hideSoftInputFromWindow dismisses the keyboard without touching
        // the activity; the layout must return exactly to the closed state.
        hideIme()
        composeRule.waitForIdle()
        assertEquals(
            "header must not move when the real IME closes",
            headerTopClosed,
            boundsTop("Chat")
        )
        assertEquals(
            "composer must return to the closed position after the IME closes",
            composerTopClosed,
            boundsTop("Nachricht")
        )
        shoot("07a_real_ime_closed_after_back")
        Thread.sleep(2_000)

        // State C: reopen the real keyboard — the layout must be stable
        // across repeated open/close cycles.
        val imeOpenedC = openRealKeyboard()
        if (imeOpenedC) awaitIme(open = true)
        assertEquals(
            "header must not pan on IME reopen",
            headerTopClosed,
            boundsTop("Chat")
        )
        if (imeOpenedC) {
            assertTrue(
                "real IME must reopen in state C: composer must move up again",
                boundsTop("Nachricht") < composerTopClosed - 500
            )
        }
        shoot("07b_real_ime_reopened")
        Thread.sleep(1_000) // host-side screencap window before the test ends
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setScreen(dark: Boolean = false) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            // The production MainActivity declares
            // android:windowSoftInputMode="adjustResize" (the chat root then
            // consumes the IME inset exactly once and nothing pans). The
            // instrumentation host activity has no such declaration, so the
            // platform default (pan) applies and the real keyboard scrolls the
            // whole window: the header keeps its root-relative bounds but
            // leaves the visible window, which makes `assertIsDisplayed()`
            // fail without any layout defect. Mirror the production window
            // mode so the test host behaves like the shipped app.
            composeRule.activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        composeRule.setContent {
            HermesAppSurface {
                HermesTheme(darkTheme = dark) {
                    ChatScreen(
                        historySource = MessageHistorySource { _, _ ->
                            MessageHistoryResult.Success(MessagePage(fixtureMessages()))
                        },
                        runController = RunSessionController(
                            chatSource = StreamingChatSource { _, _, _ -> emptyFlow() },
                            serviceStarter = noOpServiceStarter,
                            notificationCoordinator = NoOpNotificationCoordinator
                        ),
                        runStopRequester = runStopRequesterThatIsNeverReached,
                        runSteerRequester = runSteerRequesterThatIsNeverReached,
                        chatDraftRepository = NoOpChatDraftRepository(),
                        profileId = "fixture-profile-id",
                        sessionId = "fixture-session-id",
                        onBack = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Explicitly requests the IME from the app process (user-tap equivalent). */
    private fun showImeExplicitly() {
        val activity = composeRule.activity
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.showSoftInput(activity.window.decorView, InputMethodManager.SHOW_FORCED)
        }
        composeRule.waitForIdle()
    }

    /** Explicitly hides the IME from the app process. */
    private fun hideIme() {
        val activity = composeRule.activity
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        awaitIme(open = false)
    }

    /**
     * Bounded wait for the real IME inset to reach the expected state
     * (`open`: bottom > 0, `closed`: bottom == 0). Replaces fixed wall-clock
     * sleeps so the assertions are robust on a slow emulator.
     */
    private fun awaitIme(open: Boolean, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val imeBottom = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            if ((open && imeBottom > 0) || (!open && imeBottom == 0)) {
                composeRule.waitForIdle()
                return
            }
            Thread.sleep(100)
        }
        composeRule.waitForIdle()
    }

    /**
     * Opens the real keyboard and reports whether it actually did.
     *
     * On Android 13+ the IME is only shown in response to real user
     * input events; programmatic `showSoftInput` calls from an
     * instrumented test are suppressed (Gboard keeps its
     * toolbar-only state). In host-tap mode the capture script
     * performs a real `adb shell input tap` at the printed
     * coordinates; in plain regression mode (no host script) a
     * synthetic focus is attempted and the keyboard may stay hidden,
     * so the returned flag is the only signal for position
     * assertions.
     */
    private fun openRealKeyboard(): Boolean {
        if (InstrumentationRegistry.getArguments().getString("hostTap") == "true") {
            val bounds =
                composeRule.onNodeWithText("Nachricht").fetchSemanticsNode().boundsInRoot
            val cx = ((bounds.left + bounds.right) / 2).toInt()
            val cy = ((bounds.top + bounds.bottom) / 2).toInt()
            println("TAP_TARGET: $cx $cy")
            val composerTopClosed = boundsTop("Nachricht")
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                if (boundsTop("Nachricht") < composerTopClosed - 500) {
                    return true
                }
            }
            return false
        }
        composeRule.onNodeWithText("Nachricht").performClick()
        composeRule.waitForIdle()
        showImeExplicitly()
        awaitIme(open = true, timeoutMs = 3_000)
        return false
    }

    /** Injects the IME inset; status/nav bars keep their real values. */
    private fun dispatchImeInsets(ime: Int) {
        val rootInsets =
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
        val statusTop =
            rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val navBottom =
            rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .build()
        composeRule.activity.runOnUiThread {
            val content = composeRule.activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        composeRule.waitForIdle()
        Thread.sleep(250) // allow the layout pass driven by the insets
        composeRule.waitForIdle()
    }

    private fun listTop(): Int = boundsBottom("Chat")

    private fun boundsTop(text: String): Int =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top.toInt()

    private fun boundsBottom(text: String): Int =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom.toInt()

    private fun waitForText(text: String) {
        // Generous timeout: on a cold headless CI emulator the first
        // composition plus history load and auto-scroll can take well over the
        // previous 5 s, which made this file fail with ComposeTimeoutException
        // even though the layout was correct.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /**
     * Asks the host-side capture script to take a screenshot right now.
     * Callers must only invoke this while the layout is in a stable
     * state. Without a host script (plain regression runs) the marker
     * is simply ignored.
     */
    private fun shoot(name: String) {
        println("SHOOT: $name")
    }

    private fun fixtureMessages(): List<HermesChatMessage> = listOf(
        message(1, MessageRole.USER, "Kurze Frage"),
        message(
            2,
            MessageRole.ASSISTANT,
            "Lange Antwort: " +
                "Das ist eine bewusst lange Assistant-Nachricht, damit die Timeline " +
                "mehrere Zeilen füllt und der Scroll-Verhalten bei IME-Öffnung sichtbar " +
                "bleibt. Sie enthält mehrere Sätze, damit die Message-Liste eindeutig " +
                "größer ist als die verfügbare Höhe ohne IME."
        ),
        message(3, MessageRole.USER, "Noch eine Frage"),
        message(4, MessageRole.ASSISTANT, "Kurze Antwort")
    )

    private fun message(
        id: Long,
        role: MessageRole,
        content: String
    ) = HermesChatMessage(
        id = id,
        role = role,
        content = content,
        timestampSeconds = null,
        toolName = null
    )

    private val noOpServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val runStopRequesterThatIsNeverReached = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in IME-layout tests")
        }
    }

    private val runSteerRequesterThatIsNeverReached = object : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing {
            throw AssertionError("run-steer must not be reachable in IME-layout tests")
        }
    }
}
