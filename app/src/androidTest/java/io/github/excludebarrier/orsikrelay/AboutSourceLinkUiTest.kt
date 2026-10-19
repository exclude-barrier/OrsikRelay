package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.settings.AboutScreen
import io.github.excludebarrier.orsikrelay.ui.settings.PROJECT_SOURCE_URL
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The About screen's source row is a link to the public project page.
 *
 * The opener is a test seam, so no intent leaves the device; the test proves
 * the exact URL that would be opened and that the row is a real 48 dp
 * clickable with button semantics.
 */
class AboutSourceLinkUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceRowIsAVisibleClickableLinkToTheProjectPage() {
        val opened = mutableListOf<String>()
        setAbout(opened::add)

        composeRule.onNodeWithText(context.getString(R.string.settings_source_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText("github.com/exclude-barrier/OrsikRelay")
            .assertIsDisplayed()

        val row = composeRule.onNodeWithTag("about_source_code").fetchSemanticsNode()
        assertEquals(
            "the source row must expose button semantics",
            Role.Button,
            row.config[SemanticsProperties.Role]
        )
        val bounds = composeRule.onNodeWithTag("about_source_code").getBoundsInRoot()
        val minHeightPx = with(composeRule.density) { 48.dp.toPx() }
        with(composeRule.density) {
            assertTrue(
                "the source row must offer at least 48 dp of click height",
                (bounds.bottom - bounds.top).toPx() >= minHeightPx
            )
        }

        composeRule.onNodeWithTag("about_source_code").performClick()
        assertEquals(
            "exactly the public HTTPS project URL is opened",
            listOf(PROJECT_SOURCE_URL),
            opened
        )
    }

    @Test
    fun reportBugRowOpensAPrefilledIssueWithVersionsOnly() {
        val opened = mutableListOf<String>()
        setAbout(opened::add)

        composeRule.onNodeWithText(context.getString(R.string.settings_report_bug_label))
            .assertIsDisplayed()
        val row = composeRule.onNodeWithTag("about_report_bug").fetchSemanticsNode()
        assertEquals(
            "the report row must expose button semantics",
            Role.Button,
            row.config[SemanticsProperties.Role]
        )
        val bounds = composeRule.onNodeWithTag("about_report_bug").getBoundsInRoot()
        val minHeightPx = with(composeRule.density) { 48.dp.toPx() }
        with(composeRule.density) {
            assertTrue(
                "the report row must offer at least 48 dp of click height",
                (bounds.bottom - bounds.top).toPx() >= minHeightPx
            )
        }
        composeRule.onNodeWithTag("about_report_bug").performClick()

        val url = opened.single()
        assertTrue(url.startsWith("$PROJECT_SOURCE_URL/issues/new?title=&body="))
        val body = java.net.URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("App: "))
        assertTrue(body.contains("Android: "))
        assertTrue(body.contains("Device: "))
    }

    private fun setAbout(onAllowedLinkOpen: (String) -> Unit) {
        composeRule.setContent {
            HermesTheme {
                AboutScreen(
                    versionName = "fixture-version",
                    onBack = {},
                    onAllowedLinkOpen = onAllowedLinkOpen
                )
            }
        }
    }
}
