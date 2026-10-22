package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import io.github.excludebarrier.orsikrelay.ui.chat.TurnActivityRow
import io.github.excludebarrier.orsikrelay.ui.chat.TurnActivity
import android.os.SystemClock
import androidx.compose.ui.test.performClick
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onAllNodesWithText
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.activity.ActivityScreen
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import kotlinx.coroutines.flow.emptyFlow
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.onboarding.WelcomeScreen
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import io.github.excludebarrier.orsikrelay.data.settings.ChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.MaterialYouSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.ScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.settings.SettingsScreen
import io.github.excludebarrier.orsikrelay.ui.settings.rememberChatTextSizeController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberLanguageController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberMaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.navigation.AppBottomBar
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.ui.home.HomeConnection
import io.github.excludebarrier.orsikrelay.ui.home.HomeRecent
import io.github.excludebarrier.orsikrelay.ui.home.HomeRunItem
import io.github.excludebarrier.orsikrelay.ui.home.HomeScreen
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import io.github.excludebarrier.orsikrelay.ui.home.HomeUiState
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/**
 * Capture-only: the design-v2 screens rendered like the app (root surface
 * with safe-drawing padding, the bottom bar, theme-driven system-bar icons)
 * for the comparison with the design review's target images. PNGs land in
 * the app's external files dir under `design-v2/`.
 */
class DesignV2ShotsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis() / 1000

    private val home = HomeUiState(
        serverName = "Heimserver",
        connection = HomeConnection.Connected,
        runs = listOf(
            HomeRunItem("a", "Docker-Updates einspielen", awaitingApproval = true, uncertain = false),
            HomeRunItem("b", "Backup prüfen", awaitingApproval = false, uncertain = false)
        ),
        recent = HomeRecent.Ready(
            listOf(
                HermesSession("s1", "Wochenplanung", "telegram", now - 600, 34, true),
                HermesSession("s2", "Docker-Updates", "telegram", now - 90_000, 8, false),
                HermesSession("s3", "Server-Wartung", "cron", now - 600_000, 5, false)
            )
        ),
        hostMetrics = HostMetricsResult.Ready(
            HostMetrics(
                cpuPercent = 23.4, cores = 6, memoryUsedBytes = 12L * 1024 * 1024 * 1024,
                memoryTotalBytes = 32L * 1024 * 1024 * 1024, gpu = null,
                services = listOf(ServiceState("hermes-gateway", true), ServiceState("orsik-mobile-bridge", true))
            )
        )
    )

    @Composable
    private fun App(dark: Boolean, tab: AppScreen?, approvalPending: Boolean = true, content: @Composable (Modifier) -> Unit) {
        HermesTheme(darkTheme = dark) {
            SystemBarAppearance(dark)
            HermesAppSurface {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (tab != null) AppBottomBar(current = tab, approvalPending = approvalPending, onSelect = {})
                    }
                ) { inner -> content(Modifier.padding(inner).consumeWindowInsets(inner)) }
            }
        }
    }

    @Composable
    private fun Home(modifier: Modifier) {
        HomeScreen(
            state = home, onDraftChange = {}, onSend = {}, onAttach = {}, onRemoveAttachment = {},
            onOpenRun = {}, onOpenSession = {}, onOpenAllSessions = {}, onSwitchServer = {},
            onOpenSettings = {}, onRetry = {}, modifier = modifier
        )
    }

    private fun shot(name: String, before: () -> Unit = {}, content: @Composable () -> Unit) {
        composeRule.setContent { content() }
        composeRule.waitForIdle()
        before()
        // Capture-only: let the first frame and the system bars settle.
        Thread.sleep(800)
        composeRule.waitForIdle()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val argb = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(screen, 0f, 0f, null)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-v2")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scrollHome() {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(hasTestTag(HomeTags.RECENT_ROW))
    }

    @CaptureOnly @Test fun homeDark() = shot("01-home-dark") { App(true, AppScreen.Home) { Home(it) } }
    @CaptureOnly @Test fun homeLight() = shot("01-home-light") { App(false, AppScreen.Home) { Home(it) } }
    @CaptureOnly @Test fun homeServerCardDark() = shot("02b-home-serverwerte-dark", {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(androidx.compose.ui.test.hasTestTag(HomeTags.SERVER_STRIP))
    }) { App(true, AppScreen.Home) { Home(it) } }
    @CaptureOnly @Test fun homeServerCardLargeFontDark() = shot("02c-home-serverwerte-grossschrift-dark", {
        composeRule.onNodeWithTag(HomeTags.LIST).performScrollToNode(androidx.compose.ui.test.hasTestTag(HomeTags.SERVER_STRIP))
    }) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)
        ) { App(true, AppScreen.Home) { Home(it) } }
    }
    @CaptureOnly @Test fun homeScrolledDark() = shot("02-home-gescrollt-dark", ::scrollHome) { App(true, AppScreen.Home) { Home(it) } }
    @CaptureOnly @Test fun homeScrolledLight() = shot("02-home-gescrollt-light", ::scrollHome) { App(false, AppScreen.Home) { Home(it) } }

    private val agentSessions = listOf(
        HermesSession("a1", "Backup prüfen", "cli", now - 3_000, 12, true),
        HermesSession("a2", "Wochenplanung", "telegram", now - 9_000, 34, false),
        HermesSession("a3", "Docker-Updates", "telegram", now - 90_000, 8, false),
        HermesSession("a4", "Rezept für Sonntag", "cli", now - 260_000, 21, false)
    )

    @Composable
    private fun Agents(modifier: Modifier) {
        SessionsScreen(
            pageSource = SessionPageSource { _, _, _ -> SessionListResult.Success(SessionPage(agentSessions, hasMore = false)) },
            profileId = "fixture",
            creator = SessionCreator { _, _ -> SessionCreateResult.Failure(SessionCreateError.Network) },
            runningSessionId = "a1",
            modifier = modifier
        )
    }

    @CaptureOnly @Test fun agentsDark() = shot("03-agenten-dark") { App(true, AppScreen.Sessions) { Agents(it) } }
    @CaptureOnly @Test fun agentsLight() = shot("03-agenten-light") { App(false, AppScreen.Sessions) { Agents(it) } }

    private val chatHistory = MessageHistorySource { _, _ ->
        MessageHistoryResult.Success(
            MessagePage(
                listOf(
                    HermesChatMessage(1, MessageRole.USER, "Wie ist der aktuelle Speicherstatus des Servers?", null, null),
                    HermesChatMessage(2, MessageRole.TOOL, "", null, "Terminal"),
                    HermesChatMessage(
                        3,
                        MessageRole.ASSISTANT,
                        "**Speicherstatus: alles gut**\n\nDer Server läuft stabil. Die wichtigsten Werte:\n\n" +
                            "- **Festplatte:** 12 % belegt\n- **Arbeitsspeicher:** 3,1 GB frei\n- **Systemlast:** gering\n\n" +
                            "```bash\ndf -h /srv\n/dev/vda1  50G  6,2G  44G  12%\n```",
                        null,
                        null
                    )
                )
            )
        )
    }

    private val toolHistory = MessageHistorySource { _, _ ->
        MessageHistoryResult.Success(
            MessagePage(
                listOf(
                    HermesChatMessage(1, MessageRole.USER, "Räum bitte die alten Logs auf.", null, null),
                    HermesChatMessage(2, MessageRole.TOOL, "", null, "terminal"),
                    HermesChatMessage(3, MessageRole.TOOL, "", null, "read_file"),
                    HermesChatMessage(4, MessageRole.TOOL, "", null, "terminal"),
                    HermesChatMessage(5, MessageRole.TOOL, "", null, "search_files"),
                    HermesChatMessage(6, MessageRole.ASSISTANT, "Erledigt: **14 alte Logdateien** entfernt, 2,3 GB frei.", null, null)
                )
            )
        )
    }

    @Composable
    private fun Chat(modifier: Modifier, history: MessageHistorySource = chatHistory) {
        ChatScreen(
            historySource = history,
            runController = RunSessionController(
                chatSource = StreamingChatSource { _, _, _ -> emptyFlow() },
                serviceStarter = object : RunForegroundServiceStarter {
                    override fun start(profileId: String, sessionId: String) {}
                    override fun stop() {}
                },
                notificationCoordinator = NoOpNotificationCoordinator
            ),
            runStopRequester = object : RunStopRequester {
                override suspend fun stopRun(profileId: String, runId: String): Nothing = throw AssertionError("unused")
            },
            runSteerRequester = object : RunSteerRequester {
                override suspend fun steerRun(profileId: String, runId: String, text: String): Nothing = throw AssertionError("unused")
            },
            chatDraftRepository = NoOpChatDraftRepository(),
            profileId = "fixture",
            sessionId = "fixture-chat",
            sessionTitle = "Speicherstatus",
            serverName = "Heimserver",
            onBack = {},
            modifier = modifier
        )
    }

    private fun waitForChat() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Speicherstatus: alles gut", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTools(expand: Boolean) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("14 alte Logdateien", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (expand) composeRule.onNodeWithTag("chatToolGroupToggle:tools:server:2").performClick()
    }

    @CaptureOnly @Test fun chatToolsDark() = shot("04b-chat-tools-dark", { waitForTools(false) }) { App(true, null) { Chat(it, toolHistory) } }
    @CaptureOnly @Test fun chatToolsOpenLight() = shot("04c-chat-tools-offen-light", { waitForTools(true) }) { App(false, null) { Chat(it, toolHistory) } }

    @CaptureOnly @Test fun workingRowDark() = shot("04d-arbeitet-dark") {
        HermesTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp)) {
                    TurnActivityRow(TurnActivity.Working, SystemClock.elapsedRealtime() - 12_000, null)
                    TurnActivityRow(TurnActivity.Thinking, SystemClock.elapsedRealtime() - 75_000, null)
                    TurnActivityRow(TurnActivity.Tool, SystemClock.elapsedRealtime() - 4_000, "terminal")
                }
            }
        }
    }

    /** The relay mid hand-off: animations on, the test clock stopped at a fixed moment. */
    @CaptureOnly @Test fun workingRelayMovingDark() {
        fun scale(value: String) {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("settings put global animator_duration_scale $value")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        scale("1")
        try {
            composeRule.mainClock.autoAdvance = false
            shot("04e-staffel-bewegt-dark", { composeRule.mainClock.advanceTimeBy(560) }) {
                HermesTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp)) {
                            TurnActivityRow(TurnActivity.Working, SystemClock.elapsedRealtime() - 12_000, null)
                            TurnActivityRow(TurnActivity.Thinking, SystemClock.elapsedRealtime() - 75_000, null)
                            TurnActivityRow(TurnActivity.Tool, SystemClock.elapsedRealtime() - 4_000, "terminal")
                        }
                    }
                }
            }
        } finally {
            scale("0")
        }
    }

    @CaptureOnly @Test fun chatDark() = shot("04-chat-dark", ::waitForChat) { App(true, null) { Chat(it) } }
    @CaptureOnly @Test fun chatLight() = shot("04-chat-light", ::waitForChat) { App(false, null) { Chat(it) } }

    private val serverAdmin = object : ProfileAdmin {
        override fun listProfiles() = listOf(
            ServerProfile.create("p1", "Heimserver", "https://hermes.example.ts.net:8443"),
            ServerProfile.create("p2", "Büro-Server", "https://office.example.ts.net:8443")
        )
        override fun deleteProfile(profileId: String) = Unit
        override fun selectActiveServer(profileId: String) = Unit
        override fun credentialKind(profileId: String) =
            if (profileId == "p1") ProfileCredentialKind.API_KEY else ProfileCredentialKind.DEVICE_CREDENTIAL
    }

    @Composable
    private fun Servers(modifier: Modifier) {
        ProfilesScreen(admin = serverAdmin, onBack = null, activeProfileId = "p1", activeReachable = true, modifier = modifier)
    }

    private fun waitForServers() {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Büro-Server").fetchSemanticsNodes().isNotEmpty() }
    }

    @CaptureOnly @Test fun serverDark() = shot("05-server-dark", ::waitForServers) { App(true, AppScreen.Server) { Servers(it) } }
    @CaptureOnly @Test fun serverLight() = shot("05-server-light", ::waitForServers) { App(false, AppScreen.Server) { Servers(it) } }

    @Composable
    private fun Settings(modifier: Modifier) {
        val language = rememberLanguageController(object : LanguageSettingStore {
            override fun read(): String? = null
            override fun write(tag: String?) = Unit
        }) { }
        val theme = rememberThemeModeController(object : ThemeSettingStore {
            override fun read() = ThemeMode.System
            override fun write(mode: ThemeMode) = Unit
        })
        val textSize = rememberChatTextSizeController(object : ChatTextSizeSettingStore {
            override fun read() = ChatTextSize.Normal
            override fun write(size: ChatTextSize) = Unit
        })
        val protection = rememberScreenProtectionController(object : ScreenProtectionSettingStore {
            override fun read() = true
            override fun write(enabled: Boolean) = Unit
        })
        val materialYou = rememberMaterialYouController(object : MaterialYouSettingStore {
            override fun read() = false
            override fun write(enabled: Boolean) = Unit
        })
        SettingsScreen(
            languageController = language,
            themeController = theme,
            chatTextSizeController = textSize,
            screenProtectionController = protection,
            appLockController = AppLockController(initiallyEnabled = true, persist = {}),
            materialYouController = materialYou,
            appAuthenticator = object : AppAuthenticator {
                override fun canAuthenticate() = true
                override fun authenticate(onResult: (Boolean) -> Unit) = onResult(false)
            },
            onBack = {},
            onOpenAbout = {},
            modifier = modifier
        )
    }

    @CaptureOnly @Test fun settingsDark() = shot("06-einstellungen-dark") { App(true, null) { Settings(it) } }
    @CaptureOnly @Test fun settingsLight() = shot("06-einstellungen-light") { App(false, null) { Settings(it) } }

    @CaptureOnly @Test fun welcomeDark() = shot("07-willkommen-dark") { App(true, null) { WelcomeScreen(onScan = {}, onEnterCode = {}, modifier = it, cameraAvailable = true) } }
    @CaptureOnly @Test fun welcomeLight() = shot("07-willkommen-light") { App(false, null) { WelcomeScreen(onScan = {}, onEnterCode = {}, modifier = it, cameraAvailable = true) } }

    @CaptureOnly @Test fun activityDark() = shot("08-aktivitaet-dark") {
        App(true, AppScreen.Activity) { ActivityScreen(runs = home.runs, onOpenRun = {}, modifier = it) }
    }
    @CaptureOnly @Test fun activityEmptyLight() = shot("08-aktivitaet-leer-light") {
        App(false, AppScreen.Activity, approvalPending = false) { ActivityScreen(runs = emptyList(), onOpenRun = {}, modifier = it) }
    }
}
