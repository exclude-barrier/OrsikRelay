package io.github.excludebarrier.orsikrelay

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

    private fun showFiles(source: FilesSource, startPath: String = "", onBack: () -> Unit = {}) {
        composeRule.setContent {
            HermesTheme { FilesRoute(profileId = "p1", source = source, startPath = startPath, onBack = onBack) }
        }
    }

    @Test
    fun foldersOpenAndBackGoesUpBeforeLeaving() {
        val source = FakeFiles()
        var left = 0
        showFiles(source, onBack = { left++ })
        composeRule.onNodeWithText("readme.txt").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.onNodeWithText("bild.png").assertIsDisplayed()
        composeRule.onNodeWithText("/docs").assertIsDisplayed()
        composeRule.onNodeWithTag("files_back").performClick()
        composeRule.onNodeWithText("readme.txt").assertIsDisplayed()
        assertEquals(0, left)
        composeRule.onNodeWithTag("files_back").performClick()
        composeRule.runOnIdle { assertEquals(1, left) }
        assertEquals(listOf("", "docs", ""), source.listed.toList())
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
    fun textAndImagesArePreviewedAndCloseBackToTheFolder() {
        val source = FakeFiles()
        showFiles(source)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "readme.txt").performClick()
        composeRule.onNodeWithTag(FilesTags.TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("Hallo Datei äöü").assertIsDisplayed()
        composeRule.onNodeWithTag(FilesTags.DOWNLOAD).assertIsDisplayed()
        composeRule.onNodeWithTag("files_viewer_back").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "bild.png").performClick()
        composeRule.onNodeWithTag(FilesTags.IMAGE).assertIsDisplayed()
        assertEquals(listOf("readme.txt", "docs/bild.png"), source.read.toList())
    }

    @Test
    fun theStatesNameWhatHappened() {
        val source = FakeFiles()
        source.listResult = { FilesResult.Unsupported }
        showFiles(source)
        composeRule.onNodeWithText(context.getString(R.string.files_unsupported_title)).assertIsDisplayed()
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
        composeRule.onNodeWithText(context.getString(R.string.files_empty)).assertIsDisplayed()
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
