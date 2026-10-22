package io.github.excludebarrier.orsikrelay

import android.content.ClipDescription
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.AssistantMarkdownMessage
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_COPY
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_HEADER
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.rememberSecureUriHandler
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.TAG_CODE_BODY
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the assistant markdown renderer.
 *
 * These exercise the real renderer surface (not a full ChatScreen) so they
 * stay focused: element rendering, code-block copy, the https-only link seam,
 * streaming-tolerant partial markdown, and light/dark. The markdown state
 * parses asynchronously, so every assertion goes through [waitForText], which
 * pumps the main looper until the rendered node appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMarkdownUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
    fun h1RendersAtTheTitleLargeFontSize() {
        var titleLargeSize: TextUnit? = null
        composeRule.setContent {
            HermesTheme {
                titleLargeSize = MaterialTheme.typography.titleLarge.fontSize
                AssistantMarkdownMessage("# Speicherstatus")
            }
        }
        waitForText("Speicherstatus")

        val node = composeRule
            .onNodeWithText("Speicherstatus", useUnmergedTree = true)
            .fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("no GetTextLayoutResult action on the rendered h1", action != null)
        val layouts = ArrayList<TextLayoutResult>()
        action!!.action?.invoke(layouts)
        assertTrue("no TextLayoutResult for the rendered h1", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals(titleLargeSize, layout.layoutInput.style.fontSize)
        }
    }

    @Test
    fun codeBlocksAndInlineCodeUseRobotoMono() {
        renderMarkdown("Nutze `df -h` hier.\n\n```bash\ndf -h /srv\n```")
        waitForText("df -h /srv")

        fun families(node: androidx.compose.ui.semantics.SemanticsNode) =
            ArrayList<TextLayoutResult>().also { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it) }
                .map { it.layoutInput.style.fontFamily }
        assertEquals(listOf(OrsikMono), families(composeRule.onNodeWithTag(TAG_CODE_BODY).fetchSemanticsNode()))
        // Inline code is a span inside the paragraph.
        val paragraph = ArrayList<TextLayoutResult>().also {
            composeRule.onNodeWithText("Nutze", substring = true, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it)
        }.first()
        assertTrue(paragraph.layoutInput.text.spanStyles.any { it.item.fontFamily == OrsikMono })
    }

    @Test
    fun codeHeaderIs44DpHighAndCopyKeepsA48DpTouchTarget() {
        renderMarkdown("```kotlin\nval a = 1\n```")
        waitForText("KOTLIN")

        val headerHeightDp = with(composeRule.density) {
            composeRule.onNodeWithTag(TAG_CODE_HEADER).fetchSemanticsNode().boundsInRoot.height / density
        }
        assertTrue(
            "visible code header must be 44 dp high (design v2), was $headerHeightDp dp",
            abs(headerHeightDp - 44f) <= 1f
        )

        val copy = composeRule.onNodeWithTag(TAG_CODE_COPY).fetchSemanticsNode().touchBoundsInRoot
        val copyWidthDp = with(composeRule.density) { copy.width / density }
        val copyHeightDp = with(composeRule.density) { copy.height / density }
        assertTrue("copy touch target width must be >= 48 dp, was $copyWidthDp dp", copyWidthDp >= 48f)
        assertTrue("copy touch target height must be >= 48 dp, was $copyHeightDp dp", copyHeightDp >= 48f)
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
        waitForText(context.getString(R.string.chat_code_copy))
    }

    @Test
    fun codeBlockWithoutLanguageShowsFallbackLabel() {
        renderMarkdown("```\nplain text\n```")
        waitForText(context.getString(R.string.chat_code_language_fallback))
        waitForText("plain text")
    }

    @Test
    fun copyButtonCopiesExactCodeWithoutFence() {
        val clipboard = RecordingClipboard()
        val code = "val x = 1\nprintln(x)"
        composeRule.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    AssistantMarkdownMessage("```kotlin\n$code\n```")
                }
            }
        }
        waitForText(context.getString(R.string.chat_code_copy))
        composeRule.onNodeWithText(context.getString(R.string.chat_code_copy)).performClick()

        // The clipboard must receive exactly the code body, not the fence or
        // the language tag.
        composeRule.waitUntil(timeoutMillis = 5_000) { clipboard.last != null }
        val clip = checkNotNull(clipboard.last).clipData
        assertEquals(code, clip.getItemAt(0).text.toString())
        // Code can hold secrets: Android 13+ must not preview it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        }

        // Discreet copy feedback, not a toast.
        waitForText(context.getString(R.string.chat_code_copied))
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
    private class RecordingClipboard : Clipboard {
        var last: ClipEntry? = null
        override val nativeClipboard: NativeClipboard
            get() = throw UnsupportedOperationException("not used by the copy action")
        override suspend fun getClipEntry(): ClipEntry? = last
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            last = clipEntry
        }
    }
}
