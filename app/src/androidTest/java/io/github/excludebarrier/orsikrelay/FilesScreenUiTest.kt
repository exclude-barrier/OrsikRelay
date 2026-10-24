package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.components.ComposerDockTags
import io.github.excludebarrier.orsikrelay.ui.files.FilesAskResult
import io.github.excludebarrier.orsikrelay.ui.files.FilesHermes
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectCommit
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.ui.files.FilesRoute
import io.github.excludebarrier.orsikrelay.ui.files.FilesTags
import io.github.excludebarrier.orsikrelay.ui.files.ProjectsRoute
import io.github.excludebarrier.orsikrelay.ui.files.ProjectsTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The read-only file browser and project list with a fake source. */
@RunWith(AndroidJUnit4::class)
class FilesScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeFiles : FilesSource {
        val listed = CopyOnWriteArrayList<String>()
        val read = CopyOnWriteArrayList<String>()
        var listResult: (String) -> FilesResult<FileListing> = { path ->
            FilesResult.Ready(
                when (path) {
                    "" -> FileListing("", listOf(
                        FileEntry("docs", FileKind.Directory, null, 1_700_000_000),
                        FileEntry("readme.txt", FileKind.File, 12, 1_700_000_000),
                        FileEntry("etc-link", FileKind.Other, null, null)
                    ), false)
                    "docs" -> FileListing("docs", listOf(FileEntry("bild.png", FileKind.File, 70, null)), false)
                    else -> FileListing(path, emptyList(), false)
                }
            )
        }
        var readResult: (String) -> FilesResult<FileContent> = { path ->
            if (path.endsWith(".png")) {
                FilesResult.Ready(FileContent(PNG, FileContentKind.Image))
            } else {
                FilesResult.Ready(FileContent("Hallo Datei äöü".toByteArray(), FileContentKind.Text))
            }
        }

        override fun list(profileId: String, path: String): FilesResult<FileListing> {
            listed += path
            return listResult(path)
        }

        override fun read(profileId: String, path: String): FilesResult<FileContent> {
            read += path
            return readResult(path)
        }

        override fun projects(profileId: String): FilesResult<ProjectListing> = FilesResult.Ready(
            ProjectListing(listOf(
                Project("demo-app", "code/demo-app", "main", true, ProjectCommit("abc1234", 1_700_000_000, "Erster Stand")),
                Project("docs-site", "docs-site", null, null, null)
            ), false)
        )
    }

    private val asked = CopyOnWriteArrayList<Pair<String, String>>()
    private val sessions = CopyOnWriteArrayList<Pair<String, String>>()
    private val hermes = FilesHermes(
        ask = { path, question -> asked += path to question; FilesAskResult.Opened },
        openInSession = { title, path -> sessions += title to path; true }
    )

    private fun showFiles(source: FilesSource, startPath: String = "", onBack: () -> Unit = {}) {
        composeRule.setContent {
            HermesTheme { FilesRoute(profileId = "p1", source = source, startPath = startPath, onBack = onBack, hermes = hermes) }
        }
    }

    private fun names(): List<String> = composeRule.onAllNodes(hasTestTagPrefix(FilesTags.ENTRY_PREFIX))
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix(FilesTags.ENTRY_PREFIX) }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun breadcrumbsAndBackWalkTheFolders() {
        val source = FakeFiles()
        var left = 0
        showFiles(source, onBack = { left++ })
        composeRule.onNodeWithText("readme.txt").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.onNodeWithText("bild.png").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.CRUMB_PREFIX + "docs").assertIsSelected()
        // The root crumb jumps back.
        composeRule.onNodeWithTag(FilesTags.CRUMB_PREFIX).performClick()
        composeRule.onNodeWithText("readme.txt").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.onNodeWithTag("files_back").performClick()
        composeRule.onNodeWithText("readme.txt").assertIsDisplayed()
        assertEquals(0, left)
        composeRule.onNodeWithTag("files_back").performClick()
        composeRule.runOnIdle { assertEquals(1, left) }
    }

    @Test
    fun sortingAndSearchKeepFoldersFirst() {
        val source = FakeFiles()
        source.listResult = {
            FilesResult.Ready(FileListing("", listOf(
                FileEntry("b-small.txt", FileKind.File, 10, 3_000),
                FileEntry("a-big.bin", FileKind.File, 9_000, 1_000),
                FileEntry("z-folder", FileKind.Directory, null, 500),
                FileEntry("c-new.png", FileKind.File, 500, 9_000)
            ), false, "/srv"))
        }
        showFiles(source)
        composeRule.waitUntil(5_000) { names().size == 4 }
        assertEquals(listOf("z-folder", "a-big.bin", "b-small.txt", "c-new.png"), names())
        composeRule.onNodeWithTag(FilesTags.SORT).performClick()
        composeRule.onNodeWithTag(FilesTags.SORT_OPTION_PREFIX + "date").performClick()
        composeRule.waitUntil(5_000) { names().getOrNull(1) == "c-new.png" }
        assertEquals(listOf("z-folder", "c-new.png", "b-small.txt", "a-big.bin"), names())
        composeRule.onNodeWithTag(FilesTags.SORT).performClick()
        composeRule.onNodeWithTag(FilesTags.SORT_OPTION_PREFIX + "size").performClick()
        composeRule.waitUntil(5_000) { names().getOrNull(1) == "a-big.bin" }
        assertEquals(listOf("z-folder", "a-big.bin", "c-new.png", "b-small.txt"), names())
        composeRule.onNodeWithTag(FilesTags.SEARCH).performTextInput("png")
        composeRule.waitUntil(5_000) { names() == listOf("c-new.png") }
        composeRule.onNodeWithTag(FilesTags.LAYOUT).performClick()
        composeRule.onNodeWithTag(FilesTags.GRID).assertExists()
    }

    @Test
    fun aLinkOutsideIsShownButCannotBeOpened() {
        val source = FakeFiles()
        showFiles(source)
        composeRule.onNodeWithText(context.getString(R.string.files_kind_other)).assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "etc-link").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), source.read.toList()) }
    }

    @Test
    fun markdownHasPreviewAndNumberedSource() {
        val source = FakeFiles()
        source.listResult = { FilesResult.Ready(FileListing("", listOf(FileEntry("notes.md", FileKind.File, 30, 1_700_000_000)), false, "/srv")) }
        source.readResult = { FilesResult.Ready(FileContent("# Notizen\n\nHallo **Welt**".toByteArray(), FileContentKind.Text)) }
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "notes.md").performClick()
        composeRule.onNodeWithTag(FilesTags.MARKDOWN).assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.MODE_PREFIX + "source").performClick()
        composeRule.onNodeWithTag(FilesTags.TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("# Notizen").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.files_read_only_badge)).assertIsDisplayed()
    }

    @Test
    fun plainTextOpensAsSourceAndImagesAsAZoomableImage() {
        val source = FakeFiles()
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "readme.txt").performClick()
        composeRule.onNodeWithTag(FilesTags.TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("Hallo Datei äöü").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.DOWNLOAD).assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.SHARE).assertIsDisplayed()
        composeRule.onNodeWithTag("files_viewer_back").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "bild.png").performClick()
        composeRule.onNodeWithTag(FilesTags.IMAGE).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.files_info_dimensions)).assertIsDisplayed()
        composeRule.onNodeWithText("1 × 1").assertIsDisplayed()
        assertEquals(listOf("readme.txt", "docs/bild.png"), source.read.toList())
    }

    @Test
    fun aBinaryFileHasNoPreviewButDownloadAndShare() {
        val source = FakeFiles()
        source.listResult = { FilesResult.Ready(FileListing("", listOf(FileEntry("blob.bin", FileKind.File, 4121, null)), false, "/srv")) }
        source.readResult = { FilesResult.Ready(FileContent(ByteArray(4121), FileContentKind.Binary)) }
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "blob.bin").performClick()
        composeRule.onNodeWithTag(FilesTags.BINARY).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.files_no_preview_title)).assertIsDisplayed()
        composeRule.onNodeWithText("/srv/blob.bin").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.files_download_short)).assertIsEnabled()
    }

    @Test
    fun theActionSheetOffersPathAndSessionAndShowsRenameAndDeleteAsSoon() {
        val source = FakeFiles()
        source.listResult = { FilesResult.Ready(FileListing("", listOf(FileEntry("readme.txt", FileKind.File, 12, null)), false, "/srv")) }
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.MORE_PREFIX + "readme.txt").performClick()
        composeRule.onNodeWithTag(FilesTags.SHEET).assertExists()
        composeRule.onNodeWithText("/srv/readme.txt").assertExists()
        composeRule.onNodeWithTag(FilesTags.ACTION_RENAME).assertHasNoClickAction()
        composeRule.onNodeWithTag(FilesTags.ACTION_DELETE).assertHasNoClickAction()
        composeRule.onAllNodesWithText(context.getString(R.string.files_soon)).assertCountEquals(2)
        composeRule.onNodeWithTag(FilesTags.ACTION_SESSION).performClick()
        composeRule.waitUntil(5_000) { sessions.isNotEmpty() }
        assertEquals("readme.txt" to "/srv/readme.txt", sessions.single())
        composeRule.runOnIdle { assertEquals(emptyList<String>(), source.read.toList()) }
    }

    @Test
    fun theComposerAsksHermesAboutTheFolder() {
        val source = FakeFiles()
        source.listResult = { path -> FilesResult.Ready(FileListing(path, emptyList(), false, "/srv")) }
        showFiles(source, startPath = "docs")
        composeRule.onNodeWithText(context.getString(R.string.files_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.COMPOSER).performTextInput("Was liegt hier?")
        composeRule.onNodeWithTag(ComposerDockTags.SEND).performClick()
        composeRule.waitUntil(5_000) { asked.isNotEmpty() }
        assertEquals("/srv/docs" to "Was liegt hier?", asked.single())
    }

    @Test
    fun theStatesNameWhatHappened() {
        val source = FakeFiles()
        source.listResult = { FilesResult.Unsupported }
        showFiles(source)
        composeRule.onNodeWithText(context.getString(R.string.files_unsupported_title)).assertIsDisplayed()
    }

    @Test
    fun aRefusedFolderOffersTheWayBack() {
        val source = FakeFiles()
        source.listResult = { path ->
            if (path == "docs/secret") FilesResult.Refused else FilesResult.Ready(FileListing(path, emptyList(), false, "/srv"))
        }
        showFiles(source, startPath = "docs/secret")
        composeRule.onNodeWithTag(FilesTags.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.files_refused_title, "/docs/secret")).assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.REFUSED_UP).performClick()
        composeRule.onNodeWithTag(FilesTags.CRUMB_PREFIX + "docs").assertIsSelected()
    }

    @Test
    fun aFailedReadOffersARetry() {
        val source = FakeFiles()
        var fail = true
        source.listResult = { path -> if (fail) FilesResult.Failed else FilesResult.Ready(FileListing(path, emptyList(), false)) }
        showFiles(source)
        composeRule.onNodeWithText(context.getString(R.string.files_failed)).assertIsDisplayed()
        fail = false
        composeRule.onNodeWithTag(FilesTags.RETRY).performClick()
        composeRule.onNodeWithText(context.getString(R.string.files_empty_title)).assertIsDisplayed()
    }

    @Test
    fun aTooLargeFileSaysSo() {
        val source = FakeFiles()
        source.readResult = { FilesResult.TooLarge }
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "readme.txt").performClick()
        composeRule.onNodeWithText(context.getString(R.string.files_too_large)).assertIsDisplayed()
    }

    @Test
    fun projectsShowBranchStateAndOpenTheirFolder() {
        val opened = CopyOnWriteArrayList<String>()
        composeRule.setContent {
            HermesTheme {
                ProjectsRoute(profileId = "p1", source = FakeFiles(), onOpenProject = { opened += it }, onBack = {})
            }
        }
        composeRule.onNodeWithText("demo-app").assertIsDisplayed()
        composeRule.onNodeWithText(
            "main · ${context.getString(R.string.projects_changed)}", substring = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Erster Stand", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.PROJECT_PREFIX + "code/demo-app").performClick()
        composeRule.runOnIdle { assertEquals(listOf("code/demo-app"), opened.toList()) }
    }

    private companion object {
        /** A 1x1 PNG. */
        val PNG: ByteArray = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT
        )
    }
}
