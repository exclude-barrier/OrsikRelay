package io.github.excludebarrier.orsikrelay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Capture-only instrumented test (excluded from CI via [CaptureOnly]) that
 * renders the REAL production screens inside [HermesTheme] with invented
 * German sample data and writes one PNG per screen to the device.
 *
 * No production code is modified: every screen is composed through its
 * existing public signature with the same synthetic seams the existing UI
 * tests use. No assertion is made — the test only exists to produce readable
 * screenshots for the README.
 *
 * Output: PNGs are written to `/sdcard/Download/orsik-readme/<name>.png`; if
 * the public Download directory is not writable (scoped storage), they fall
 * back to the app-specific external files directory. The chosen directory is
 * printed per file so the host can `adb pull` it.
 *
 * The markdown renderer parses asynchronously on `Dispatchers.Default`; a
 * confined [StandardTestDispatcher] keeps the resulting recomposition on the
 * test scheduler (pumped by `waitForIdle`/`waitUntil`) so it can never mutate
 * the composition during a measure/layout pass (see ChatMarkdownUiTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadmeScreenshotsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    @CaptureOnly
    @Test
    fun chatLight() {
        renderChat(dark = false)
        waitForText("Nachricht")
        waitForText(ASSISTANT_HEADING, substring = true)
        capture("chat")
    }

    @CaptureOnly
    @Test
    fun chatDark() {
        renderChat(dark = true)
        waitForText("Nachricht")
        waitForText(ASSISTANT_HEADING, substring = true)
        capture("chat-dark")
    }

    @CaptureOnly
    @Test
    fun approvalLight() {
        renderApproval(dark = false)
        capture("approval")
    }

    @CaptureOnly
    @Test
    fun approvalDark() {
        renderApproval(dark = true)
        capture("approval-dark")
    }

    @CaptureOnly
    @Test
    fun sessions() {
        prepareWindow()
        setScreen(dark = false) {
            SessionsScreen(
                pageSource = SessionPageSource { _, _, _ ->
                    SessionListResult.Success(SessionPage(sessionFixtures(), false))
                },
                profileId = PROFILE_ID
            )
        }
        waitForText("Backup prüfen")
        capture("sessions")
    }

    @CaptureOnly
    @Test
    fun onboarding() {
        prepareWindow()
        setScreen(dark = false) {
            OnboardingScreen(
                onValidDraft = { },
                onValidPairing = { _, _, _ -> },
                initialProfileName = "Heimserver",
                initialBaseUrl = "https://hermes.example.ts.net:8443",
                initialPairingCode = PAIRING_CODE
            )
        }
        waitForText("Server hinzufügen")
        capture("onboarding")
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun renderChat(dark: Boolean) {
        prepareWindow()
        setScreen(dark = dark) {
            ChatScreen(
                historySource = chatHistorySource(),
                runController = RunSessionController(
                    chatSource = StreamingChatSource { _, _, _ -> emptyFlow() },
                    serviceStarter = noOpServiceStarter,
                    notificationCoordinator = NoOpNotificationCoordinator
                ),
                runStopRequester = neverReachedStopRequester,
                runSteerRequester = neverReachedSteerRequester,
                chatDraftRepository = NoOpChatDraftRepository(),
                profileId = PROFILE_ID,
                sessionId = SESSION_ID,
                onBack = {}
            )
        }
    }

    private fun renderApproval(dark: Boolean) {
        val source = FakeTurnSource()
        prepareWindow()
        setScreen(dark = dark) {
            ChatScreen(
                historySource = chatHistorySource(),
                runController = RunSessionController(
                    chatSource = source,
                    serviceStarter = noOpServiceStarter,
                    notificationCoordinator = NoOpNotificationCoordinator
                ),
                runStopRequester = neverReachedStopRequester,
                runSteerRequester = neverReachedSteerRequester,
                chatDraftRepository = NoOpChatDraftRepository(),
                profileId = PROFILE_ID,
                sessionId = SESSION_ID,
                onBack = {}
            )
        }
        waitForText("Nachricht")
        waitForText(ASSISTANT_HEADING, substring = true)
        composeRule.onNodeWithText("Nachricht").performTextInput(APPROVAL_PROMPT)
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue("the synthetic turn must start", source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(
            ChatTurnEvent.RunStarted(APPROVAL_RUN_ID),
            ChatTurnEvent.ApprovalRequested(APPROVAL_REQUEST)
        )
        waitForText(APPROVAL_TITLE)
    }

    /** Mirrors the production window setup (edge-to-edge + resize for the IME). */
    private fun prepareWindow() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }

    private fun setScreen(dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            // HermesTheme must wrap HermesAppSurface (as in production
            // MainActivity), so the app surface reads the themed background.
            HermesTheme(darkTheme = dark) {
                HermesAppSurface {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    private fun capture(name: String) {
        composeRule.waitForIdle()
        // One extra idle round-trip so the surface is fully composited before
        // the frame grab.
        composeRule.waitForIdle()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("takeScreenshot() returned null for '$name'")
        val argb = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(screen, 0f, 0f, null)
        val file = writePng(argb, "$name.png")
        println("README_SHOT_SAVED: ${file.absolutePath} (${file.length()} bytes)")
    }

    /**
     * Writes [fileName] to the public Download directory when the platform
     * allows it, otherwise to the app-specific external files directory
     * (always writable). The actual file is returned so the host can pull
     * from the correct location; each capture prints the resolved path.
     */
    private fun writePng(bitmap: Bitmap, fileName: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val public = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "orsik-readme"
        )
        val publicFile = File(public, fileName)
        if (tryWritePng(bitmap, publicFile)) return publicFile
        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "orsik-readme")
        val appFile = File(appDir, fileName)
        check(tryWritePng(bitmap, appFile)) { "PNG write failed for both directories: $fileName" }
        return appFile
    }

    private fun tryWritePng(bitmap: Bitmap, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
        }
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Fixtures (invented values only — no real hosts, paths, names or keys)
    // ------------------------------------------------------------------

    private fun chatHistorySource() = MessageHistorySource { _, _ ->
        MessageHistoryResult.Success(MessagePage(chatMessages()))
    }

    private fun chatMessages(): List<HermesChatMessage> = listOf(
        HermesChatMessage(
            id = 1,
            role = MessageRole.USER,
            content = USER_QUESTION,
            timestampSeconds = null,
            toolName = null
        ),
        HermesChatMessage(
            id = 2,
            role = MessageRole.TOOL,
            content = "",
            timestampSeconds = null,
            toolName = null
        ),
        HermesChatMessage(
            id = 3,
            role = MessageRole.ASSISTANT,
            content = ASSISTANT_ANSWER,
            timestampSeconds = null,
            toolName = null
        )
    )

    private fun sessionFixtures(): List<HermesSession> {
        val now = System.currentTimeMillis() / 1_000
        return listOf(
            HermesSession("fixture-backup", "Backup prüfen", null, now - 300, 12, pinned = false),
            HermesSession("fixture-plan", "Wochenplanung", null, now - 7_200, 34, pinned = true),
            HermesSession("fixture-docker", "Docker-Updates", null, now - 86_400, 8, pinned = false),
            HermesSession("fixture-rezept", "Rezept für Sonntag", null, now - 3 * 86_400, 21, pinned = false),
            HermesSession("fixture-wartung", "Server-Wartung", null, now - 7 * 86_400, 5, pinned = false)
        )
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private val noOpServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private val neverReachedStopRequester = object : RunStopRequester {
        override suspend fun stopRun(profileId: String, runId: String): Nothing {
            throw AssertionError("run-stop must not be reachable in screenshot tests")
        }
    }

    private val neverReachedSteerRequester = object : RunSteerRequester {
        override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing {
            throw AssertionError("run-steer must not be reachable in screenshot tests")
        }
    }

    /** Synthetic streaming fake that latches and emits enqueued turn events. */
    private class FakeTurnSource : StreamingChatSource {
        val opened = CountDownLatch(1)
        @Volatile
        private var channel: Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            val events = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            channel = events
            opened.countDown()
            return flow {
                for (event in events) {
                    emit(event)
                }
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            events.forEach { channel?.trySend(it) }
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"

        const val USER_QUESTION = "Wie viel Speicher ist auf dem Server noch frei?"
        const val ASSISTANT_HEADING = "Alles im grünen Bereich"
        const val ASSISTANT_ANSWER = (
            "**Speicherplatz: Alles im grünen Bereich**\n\n" +
                "Die wichtigsten Werte auf einen Blick:\n\n" +
                "- Festplatte: 12 % belegt\n" +
                "- Arbeitsspeicher: 3,1 GB frei\n" +
                "- Systemlast: gering\n\n" +
                "```\n" +
                "Dateisystem   Größe  Belegt  Frei\n" +
                "/dev/vda1       50G    6,2G    44G\n" +
                "tmpfs          3,9G      0G   3,9G\n" +
                "```"
        )

        // Synthetic pairing code from the closed pairing alphabet (24 chars).
        const val PAIRING_CODE = "AB12CD34EF56GH78JK9MNPQR"

        const val APPROVAL_PROMPT = "Führe den Sicherheits-Check aus"
        const val APPROVAL_TITLE = "Freigabe anfragen"
        const val APPROVAL_RUN_ID = "run_0123456789abcdef0123456789abcdef"
        val APPROVAL_REQUEST = ApprovalRequest(
            runId = APPROVAL_RUN_ID,
            requestId = "fedcba9876543210fedcba9876543210",
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
    }
}
