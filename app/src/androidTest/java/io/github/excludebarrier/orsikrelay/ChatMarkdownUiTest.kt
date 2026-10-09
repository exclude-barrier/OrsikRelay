package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.rememberSecureUriHandler
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the assistant markdown renderer (P2).
 *
 * These exercise the real renderer surface (not a full ChatScreen) so they
 * stay focused: element rendering, code-block copy, the https-only link seam,
 * streaming-tolerant partial markdown, and light/dark. The markdown state
 * parses asynchronously, so every assertion goes through [waitForText], which
 * pumps the main looper until the rendered node appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMarkdownUiTest {

    // The markdown renderer parses asynchronously: `rememberMarkdownState`'s
    // `LaunchedEffect` calls `MarkdownStateImpl.parse`, which runs its work on
    // `Dispatchers.Default` and publishes the result through a StateFlow. With
    // the default `createComposeRule()` effect context (UnconfinedTestDispatcher)
    // the collection of that flow - and the recomposition it drives - resumes
    // on the Default worker, so the composition (SlotTable) can be mutated off
    // the main thread while the activity teardown disposes it on the main
    // thread. That race corrupted the SlotTable
    // (`ArrayIndexOutOfBoundsException` in `SlotWriter.moveSlotGapTo` during
    // `CompositionImpl.dispose`). A confined StandardTestDispatcher queues the
    // continuation on the test scheduler, pumped on the main thread by
    // `waitForIdle`/`waitUntil`.
    @get:Rule
    val composeRule = createComposeRule(StandardTestDispatcher())

    @Test
    fun headingAndEmphasisRenderAsText() {
        renderMarkdown("# Titel\n\n**fett** und *kursiv*")
        waitForText("Titel")
        waitForText("fett")
        waitForText("kursiv")
    }

    @Test
    fun listsRenderTheirItems() {
        renderMarkdown("- eins\n- zwei\n\n1. drei")
        waitForText("eins")
        waitForText("zwei")
        waitForText("drei")
    }

    @Test
    fun quoteAndInlineCodeRender() {
        renderMarkdown("> Zitat\n\nInline `code` hier.")
        waitForText("Zitat")
        waitForText("code")
    }

    @Test
    fun fencedCodeBlockShowsLanguageLabelAndCode() {
        renderMarkdown("```kotlin\nval a = 1\n```")
        waitForText("KOTLIN")
        waitForText("val a = 1")
        waitForText("Kopieren")
    }

    @Test
    fun codeBlockWithoutLanguageShowsFallbackLabel() {
        renderMarkdown("```\nplain text\n```")
        waitForText("CODE")
        waitForText("plain text")
    }

    @Test
    fun copyButtonCopiesExactCodeWithoutFence() {
        val clipboard = RecordingClipboardManager()
        val code = "val x = 1\nprintln(x)"
        composeRule.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    AssistantMarkdownMessage("```kotlin\n$code\n```")
                }
            }
        }
        waitForText("Kopieren")
        composeRule.onNodeWithText("Kopieren").performClick()

        // The clipboard must receive exactly the code body, not the fence or
        // the language tag.
        composeRule.waitUntil(timeoutMillis = 5_000) { clipboard.lastText != null }
        assertEquals(code, clipboard.lastText.toString())

        // Discreet copy feedback, not a toast.
        waitForText("Kopiert")
    }

    @Test
    fun secureUriHandlerRoutesAllowedAndIgnoresDangerous() {
        val opened = mutableListOf<String>()
        var handler: UriHandler? = null
        composeRule.setContent {
            handler = rememberSecureUriHandler(LocalContext.current, opened::add)
        }
        // An allowed https URL is routed to the opener seam.
        handler!!.openUri("https://example.com")
        assertEquals(listOf("https://example.com"), opened)
        // Every other scheme is a silent no-op: no opener, no crash, no intent.
        handler!!.openUri("javascript:alert(1)")
        handler!!.openUri("file:///etc/passwd")
        handler!!.openUri("intent://open")
        handler!!.openUri("data:text/html,x")
        handler!!.openUri("http://insecure.example")
        assertEquals("dangerous/insecure schemes must not open", listOf("https://example.com"), opened)
    }

    @Test
    fun markdownLinkRendersItsText() {
        renderMarkdown("Siehe [docs](https://example.com).")
        waitForText("docs")
    }

    @Test
    fun unterminatedCodeFenceKeepsContentWithoutCrash() {
        renderMarkdown("```kotlin\nval a = 1")
        // Streaming partial: the fence is not yet closed. Content must survive.
        waitForText("val a = 1")
    }

    @Test
    fun unterminatedBoldKeepsContentWithoutCrash() {
        renderMarkdown("hallo **fett")
        waitForText("hallo")
    }

    @Test
    fun plainTextStillRenders() {
        renderMarkdown("nur normaler Text")
        waitForText("normaler Text")
    }

    @Test
    fun rendersInLightTheme() {
        renderMarkdown("**fett** und `code`", darkTheme = false)
        waitForText("fett")
        waitForText("code")
    }

    @Test
    fun rendersInDarkTheme() {
        renderMarkdown("**fett** und `code`", darkTheme = true)
        waitForText("fett")
        waitForText("code")
    }

    private fun renderMarkdown(content: String, darkTheme: Boolean = false) {
        composeRule.setContent {
            HermesTheme(darkTheme = darkTheme) {
                AssistantMarkdownMessage(content)
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("expected text present: $text", true)
        composeRule.waitForIdle()
    }

    /**
     * A [ClipboardManager] that records the last text set by the renderer so
     * the copy test can assert on the exact copied content.
     */
    private class RecordingClipboardManager : ClipboardManager {
        var lastText: AnnotatedString? = null
        override fun setText(text: AnnotatedString) {
            lastText = text
        }
        override fun getText(): AnnotatedString? = lastText
    }
}
