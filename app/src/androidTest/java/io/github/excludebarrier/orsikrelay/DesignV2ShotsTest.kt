package io.github.excludebarrier.orsikrelay

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
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
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
    @CaptureOnly @Test fun homeScrolledDark() = shot("02-home-gescrollt-dark", ::scrollHome) { App(true, AppScreen.Home) { Home(it) } }
    @CaptureOnly @Test fun homeScrolledLight() = shot("02-home-gescrollt-light", ::scrollHome) { App(false, AppScreen.Home) { Home(it) } }
}
