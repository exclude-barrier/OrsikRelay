package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.chat.ChatHeader
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_SUBTITLE
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_BODY
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_TERMINAL
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_TOGGLE
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_MARKDOWN_TABLE_FIXED
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_MARKDOWN_TABLE_SCROLL
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_MARKDOWN_WARNING
import io.github.excludebarrier.orsikrelay.ui.components.TAG_WORKING_GLYPH
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Package 1 of the chat presentation: block spacing, collapsed code, shell
 * blocks into the terminal, the standing table column, the warning box,
 * link domains and blocked links, and the header's working line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPresentationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Same reason as ChatMarkdownUiTest: the renderer parses off the main
    // thread, so the effects run on a confined test dispatcher.
    @get:Rule
    val composeRule = createComposeRule(StandardTestDispatcher())

    @Test
    fun listItemsAre4DpApart() {
        render("- eins\n- zwei")
        waitForText("zwei")
        val gap = bounds("zwei").top - bounds("eins").bottom
        assertDp(4f, gap)
    }

    @Test
    fun headingsGet18DpAboveAnd6DpBelow() {
        render("Absatz eins\n\n## Titel\n\nAbsatz zwei")
        waitForText("Absatz zwei")
        assertDp(18f, bounds("Titel").top - bounds("Absatz eins").bottom)
        assertDp(6f, bounds("Absatz zwei").top - bounds("Titel").bottom)
    }

    @Test
    fun paragraphsAre12DpApart() {
        render("Erster Absatz\n\nZweiter Absatz")
        waitForText("Zweiter Absatz")
        assertDp(12f, bounds("Zweiter Absatz").top - bounds("Erster Absatz").bottom)
    }

    @Test
    fun longCodeIsCollapsedUntilTheUserExpandsIt() {
        val code = (1..16).joinToString("\n") { "line $it" }
        render("```log\n$code\n```")
        val showAll = context.resources.getQuantityString(R.plurals.chat_code_show_all, 16, 16)
        waitForText(showAll)
        val collapsed = composeRule.onNodeWithTag(TAG_CODE_BODY).fetchSemanticsNode().boundsInRoot.height

        composeRule.onNodeWithTag(TAG_CODE_TOGGLE).performClick()
        waitForText(context.getString(R.string.chat_code_collapse))
        val expanded = composeRule.onNodeWithTag(TAG_CODE_BODY).fetchSemanticsNode().boundsInRoot.height
        assertTrue("expanded $expanded must be taller than collapsed $collapsed", expanded > collapsed)

        composeRule.onNodeWithTag(TAG_CODE_TOGGLE).performClick()
        waitForText(showAll)
    }

    @Test
    fun shortCodeHasNoToggle() {
        render("```log\na\nb\nc\n```")
        waitForText("LOG")
        composeRule.onAllNodesWithTag(TAG_CODE_TOGGLE).assertCountEquals(0)
    }

    @Test
    fun aShellBlockHandsItsCommandsToTheTerminal() {
        val pasted = mutableListOf<String>()
        render("```bash\ncd /srv/stacks/paperless\ndocker compose pull\n```", onPaste = { pasted += it })
        waitForText(context.getString(R.string.chat_code_paste_terminal))

        composeRule.onNodeWithTag(TAG_CODE_TERMINAL).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("cd /srv/stacks/paperless && docker compose pull"), pasted)
    }

    @Test
    fun otherBlocksAndAMissingTerminalOfferNoPaste() {
        render("```log\ndocker compose pull\n```\n\n```bash\nls\n```")
        waitForText("LOG")
        composeRule.onAllNodesWithTag(TAG_CODE_TERMINAL).assertCountEquals(0)
    }

    @Test
    fun theFirstTableColumnStaysWhileTheOthersScroll() {
        val table = """
            | Container | Status | CPU | RAM | Update | Image |
            |---|---|---|---|---|---|
            | nextcloud | Up 2 days | 3 % | 1,2 GB | 29.0.1 | nextcloud:29-apache-very-long-tag |
            | immich-server | Up 2 days | 11 % | 2,8 GB | v1.120 | ghcr.io/immich-app/immich-server:release |
        """.trimIndent()
        render(table)
        waitForText("immich-server")
        val fixedBefore = composeRule.onNodeWithTag(TAG_MARKDOWN_TABLE_FIXED).fetchSemanticsNode().boundsInRoot
        val statusBefore = bounds("Status")

        composeRule.onNodeWithTag(TAG_MARKDOWN_TABLE_SCROLL).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        val fixedAfter = composeRule.onNodeWithTag(TAG_MARKDOWN_TABLE_FIXED).fetchSemanticsNode().boundsInRoot
        assertEquals(fixedBefore, fixedAfter)
        assertTrue("the other columns must scroll", bounds("Status").left < statusBefore.left)
    }

    @Test
    fun aWarningAlertBecomesTheWarningBox() {
        render("> [!WARNING]\n> Vorher ein Backup ziehen.")
        waitForText("Vorher ein Backup ziehen.")
        composeRule.onNodeWithTag(TAG_MARKDOWN_WARNING).assertExists()
        waitForText(context.getString(R.string.chat_markdown_warning))
    }

    @Test
    fun anHttpsLinkShowsItsDomainAndOpens() {
        val opened = mutableListOf<String>()
        render("[Release Notes](https://github.com/a/b)", onOpen = { opened += it })
        waitForText("↗ github.com")
        composeRule.onNodeWithText("Release Notes", substring = true).performTouchInput {
            click(Offset(8f, centerY))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://github.com/a/b"), opened)
    }

    @Test
    fun aNonHttpsLinkIsStruckThroughAndOnlyShowsAHint() {
        val opened = mutableListOf<String>()
        var hints = 0
        render("[alte Doku](http://example.com/doku)", onOpen = { opened += it }, onBlocked = { hints++ })
        waitForText(context.getString(R.string.chat_link_blocked))
        composeRule.onNodeWithText("alte Doku", substring = true).performTouchInput {
            click(Offset(8f, centerY))
        }
        composeRule.waitForIdle()
        assertEquals(1, hints)
        assertTrue("a blocked link must not open", opened.isEmpty())
    }

    @Test
    fun theHeaderShowsTheGlyphWhileHermesWorks() {
        header(working = true, subtitle = context.getString(R.string.chat_header_working))
        composeRule.onNodeWithTag(TAG_CHAT_SUBTITLE).assertExists()
        composeRule.onAllNodesWithTag(TAG_WORKING_GLYPH, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theIdleHeaderShowsTheServerLineWithoutGlyph() {
        header(working = false, subtitle = "Hermes · Heimserver")
        composeRule.onNodeWithText("Hermes · Heimserver").assertExists()
        composeRule.onAllNodesWithTag(TAG_WORKING_GLYPH, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun header(working: Boolean, subtitle: String) {
        composeRule.setContent {
            HermesTheme {
                ChatHeader(title = "Formatierung", subtitle = subtitle, working = working, onBack = {}, compact = false)
            }
        }
        composeRule.waitForIdle()
    }

    private fun render(
        content: String,
        onOpen: ((String) -> Unit)? = null,
        onBlocked: (() -> Unit)? = null,
        onPaste: ((String) -> Unit)? = null,
    ) {
        composeRule.setContent {
            HermesTheme {
                AssistantMarkdownMessage(
                    content = content,
                    onAllowedLinkOpen = onOpen ?: {},
                    onBlockedLinkTap = onBlocked ?: {},
                    onPasteToTerminal = onPaste,
                )
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun bounds(text: String): Rect =
        composeRule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertDp(expectedDp: Float, actualPx: Float) {
        val px = with(composeRule.density) { expectedDp.dp.toPx() }
        assertTrue("expected $expectedDp dp ($px px), was $actualPx px", abs(actualPx - px) <= 1.5f)
    }
}

