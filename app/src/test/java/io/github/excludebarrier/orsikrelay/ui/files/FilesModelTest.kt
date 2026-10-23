package io.github.excludebarrier.orsikrelay.ui.files

import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import io.github.excludebarrier.orsikrelay.domain.files.serverPath
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesModelTest {

    private fun dir(name: String, modified: Long? = null) = FileEntry(name, FileKind.Directory, null, modified)
    private fun file(name: String, size: Long?, modified: Long?) = FileEntry(name, FileKind.File, size, modified)

    private val entries = listOf(
        file("b.txt", 300, 1_000),
        dir("zeta", 5_000),
        file("A.png", 100, 3_000),
        dir("alpha", 1),
        file("c.bin", null, null),
        FileEntry("link", FileKind.Other, null, null)
    )

    @Test
    fun foldersAlwaysComeFirstThenTheChosenOrder() {
        assertEquals(listOf("alpha", "zeta", "A.png", "b.txt", "c.bin", "link"), arrange(entries, FileSort.Name, "").map { it.name })
        // Newest first; unknown dates last.
        assertEquals(listOf("zeta", "alpha", "A.png", "b.txt", "c.bin", "link"), arrange(entries, FileSort.Date, "").map { it.name })
        // Largest first; unknown sizes last, ties by name.
        assertEquals(listOf("alpha", "zeta", "b.txt", "A.png", "c.bin", "link"), arrange(entries, FileSort.Size, "").map { it.name })
    }

    @Test
    fun theSearchFiltersByNameIgnoringCase() {
        assertEquals(listOf("alpha", "zeta", "A.png"), arrange(entries, FileSort.Name, " a").map { it.name })
        assertEquals(listOf("A.png"), arrange(entries, FileSort.Name, ".PNG").map { it.name })
        assertTrue(arrange(entries, FileSort.Name, "nothing").isEmpty())
    }

    @Test
    fun typesComeFromTheKindAndTheExtension() {
        assertEquals(FileType.Folder, fileType(dir("x")))
        assertEquals(FileType.Image, fileType(file("a.JPEG", 1, 1)))
        assertEquals(FileType.Text, fileType(file("notes.md", 1, 1)))
        assertEquals(FileType.Text, fileType(file("Dockerfile", 1, 1)))
        assertEquals(FileType.Binary, fileType(file("blob.bin", 1, 1)))
        assertEquals(FileType.Other, fileType(FileEntry("l", FileKind.Other, null, null)))
        assertTrue(isMarkdown("README.MD"))
        assertFalse(isMarkdown("notes.txt"))
    }

    @Test
    fun datesSayTodayAndYesterdayAndAreLocalisedOtherwise() {
        val today = LocalDate.of(2026, 10, 23)
        val noon = today.atTime(13, 7).toEpochSecond(ZoneOffset.UTC)
        fun format(seconds: Long, locale: Locale) = formatModified(seconds, locale, ZoneOffset.UTC, today, "Heute", "Gestern")
        assertEquals("Heute, 13:07", format(noon, Locale.GERMANY))
        assertEquals("Gestern, 13:07", format(noon - 86_400, Locale.GERMANY))
        val older = format(noon - 10 * 86_400, Locale.GERMANY)
        assertTrue(older, older.contains("13.10.2026"))
    }

    @Test
    fun breadcrumbsLeadFromTheRootToTheFolder() {
        assertEquals(listOf("" to "/"), breadcrumbs("", "/"))
        assertEquals(listOf("" to "/", "srv" to "srv", "srv/docs" to "docs"), breadcrumbs("srv/docs", "/"))
    }

    @Test
    fun serverPathsJoinTheRootAndTheRelativePath() {
        assertEquals("/home/u/projects/docs/a.md", serverPath("/home/u/projects", "docs/a.md"))
        assertEquals("/home/u/projects", serverPath("/home/u/projects/", ""))
        assertEquals("/docs/a.md", serverPath(null, "docs/a.md"))
    }
}
