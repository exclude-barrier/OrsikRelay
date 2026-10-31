package io.github.excludebarrier.orsikrelay

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.components.ConnectedButtonGroup
import io.github.excludebarrier.orsikrelay.ui.components.GroupPosition
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.GroupedListItem
import io.github.excludebarrier.orsikrelay.ui.components.ListIconBox
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.components.PreviewNote
import io.github.excludebarrier.orsikrelay.ui.components.PreviewTile
import io.github.excludebarrier.orsikrelay.ui.components.StatusChip
import io.github.excludebarrier.orsikrelay.ui.components.StatusKind
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/**
 * Capture-only gallery of the design-v2 components in dark, light and with
 * a 1.5 font scale (the replacement for `@Preview`, which would need a new
 * dependency). PNGs land in the app's external files dir under
 * `component-shots/`.
 */
class ComponentShotsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Gallery() {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            OrsikLargeHeader(title = "Agenten")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkingGlyph(size = 40.dp)
                    StatusChip("Erreichbar", StatusKind.Ok)
                    StatusChip("Nicht verbunden", StatusKind.Neutral)
                }
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    listOf(GroupPosition.First, GroupPosition.Middle, GroupPosition.Last).forEach { position ->
                        GroupedListItem(
                            position = position,
                            title = "Wochenplanung",
                            supporting = "Telegram · 34 Nachrichten · 08:22",
                            onClick = {},
                            leading = { ListIconBox(OrsikIcons.Send) }
                        )
                    }
                }
                GroupedListItem(position = GroupPosition.Single, title = "Einzeln", supporting = "Single")
                ConnectedButtonGroup(
                    options = listOf("System", "Hell", "Dunkel"),
                    selected = "System",
                    label = { it },
                    onSelect = {}
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PreviewTile("Docker", OrsikIcons.Container, Modifier.weight(1f))
                    PreviewTile("Dateien", OrsikIcons.Folder, Modifier.weight(1f))
                }
                PreviewTile("Monitoring", OrsikIcons.Activity, Modifier.fillMaxWidth(), stacked = false)
                PreviewNote("Live-Systemwerte folgen in einer späteren Version.")
            }
        }
    }

    private fun shot(name: String, dark: Boolean, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesTheme(darkTheme = dark) { Gallery() }
            }
        }
        composeRule.waitForIdle()
        // Capture-only: give the first frame of a fresh activity time to draw.
        Thread.sleep(700)
        composeRule.waitForIdle()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val argb = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(screen, 0f, 0f, null)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "component-shots")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @CaptureOnly @Test fun componentsDark() = shot("components-dark", dark = true)
    @CaptureOnly @Test fun componentsLight() = shot("components-light", dark = false)
    @CaptureOnly @Test fun componentsDarkLargeFont() = shot("components-dark-font150", dark = true, fontScale = 1.5f)
}
