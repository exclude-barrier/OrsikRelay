package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SafeDrawingInsetsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appContentStartsBelowStatusBarInset() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
        }
        composeRule.setContent {
            HermesTheme {
                HermesAppSurface {
                    Text("Inset-Test")
                }
            }
        }
        composeRule.waitForIdle()

        var statusBarBottom = 0
        composeRule.runOnIdle {
            statusBarBottom = ViewCompat.getRootWindowInsets(
                composeRule.activity.window.decorView
            )?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        val bounds: Rect = composeRule
            .onNodeWithText("Inset-Test")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "content top ${bounds.top} must not overlap status bar bottom $statusBarBottom",
            bounds.top >= statusBarBottom
        )
    }
}
