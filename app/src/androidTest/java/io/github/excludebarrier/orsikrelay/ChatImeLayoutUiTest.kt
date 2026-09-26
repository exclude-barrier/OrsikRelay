package io.github.excludebarrier.orsikrelay

import android.graphics.Bitmap
import android.view.View
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
import java.io.File
import java.io.FileOutputStream
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

        // Tap the composer: the real emulator keyboard opens and the real
        // IME insets flow through the window pipeline.
        composeRule.onNodeWithText("Nachricht").performClick()
        composeRule.waitForIdle()
        Thread.sleep(3_500) // let the IME animation and insets settle
        composeRule.waitForIdle()

        val headerTopAfter = boundsTop("Chat")
        assertEquals("header must not pan when the real keyboard opens", headerTopBefore, headerTopAfter)
        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Nachricht").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
    }

    @Test
    fun imeScreenshotsForVisualVerification() {
        setScreen()
        waitForText("Kurze Antwort")
        capture("01_kb_closed_light")

        dispatchImeInsets(imeBottom)
        capture("02_ime_open_layout_light")

        // Multi-line composer while the IME region is reserved. Focusing
        // the field makes the window re-dispatch its real insets (IME
        // closed here), so the fake IME inset is re-applied afterwards.
        composeRule.onNodeWithText("Nachricht").performTextInput("Zweite Zeile\nDritte Zeile")
        composeRule.waitForIdle()
        dispatchImeInsets(imeBottom)
        Thread.sleep(1_500)
        capture("03_multiline_ime_light")

        dispatchImeInsets(0)
        composeRule.waitForIdle()
        capture("04_kb_closed_after_close")
    }

    @Test
    fun realKeyboardScreenshotLight() {
        setScreen(dark = false)
        waitForText("Kurze Antwort")
        composeRule.onNodeWithText("Nachricht").performClick()
        composeRule.waitForIdle()
        Thread.sleep(3_500)
        capture("05_real_ime_light")
    }

    @Test
    fun realKeyboardScreenshotDark() {
        setScreen(dark = true)
        waitForText("Kurze Antwort")
        composeRule.onNodeWithText("Nachricht").performClick()
        composeRule.waitForIdle()
        Thread.sleep(3_500)
        capture("06_real_ime_dark")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setScreen(dark: Boolean = false) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val dir = composeRule.activity.getExternalFilesDir(null) ?: return
        val bitmap: Bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT_SAVED: ${file.absolutePath}")
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
