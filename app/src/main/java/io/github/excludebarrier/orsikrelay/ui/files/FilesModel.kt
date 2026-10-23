package io.github.excludebarrier.orsikrelay.ui.files

import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.domain.files.FileKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What a list row shows (icon and colours), from the kind and the name. */
internal enum class FileType { Folder, Image, Text, Binary, Other }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "csv",
    "xml", "html", "css", "js", "ts", "kt", "kts", "java", "py", "sh", "bash", "rs", "go", "c", "h",
    "cpp", "sql", "gradle", "properties", "service", "dockerfile"
)

internal fun fileType(entry: FileEntry): FileType = when (entry.kind) {
    FileKind.Directory -> FileType.Folder
    FileKind.Other -> FileType.Other
    FileKind.File -> {
        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when {
            extension in IMAGE_EXTENSIONS -> FileType.Image
            extension in TEXT_EXTENSIONS || entry.name.lowercase(Locale.ROOT) in setOf("dockerfile", "makefile", "readme") -> FileType.Text
            else -> FileType.Binary
        }
    }
}

internal fun isMarkdown(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "markdown")

internal enum class FileSort { Name, Date, Size }

/**
 * Folders first, then files; within each by [sort] (newest / largest
 * first for date and size, name as the tie-break). [query] filters by
 * name, case-insensitively.
 */
internal fun arrange(entries: List<FileEntry>, sort: FileSort, query: String): List<FileEntry> {
    val needle = query.trim()
    val visible = if (needle.isEmpty()) entries else entries.filter { it.name.contains(needle, ignoreCase = true) }
    val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val order = when (sort) {
        FileSort.Name -> byName
        FileSort.Date -> compareByDescending<FileEntry> { it.modifiedAtSeconds ?: Long.MIN_VALUE }.then(byName)
        FileSort.Size -> compareByDescending<FileEntry> { it.sizeBytes ?: -1L }.then(byName)
    }
    return visible.sortedWith(compareBy<FileEntry> { it.kind != FileKind.Directory }.then(order))
}

/** "Heute, 13:07", "Gestern, 09:15" or a localised medium date with time. */
internal fun formatModified(
    epochSeconds: Long,
    locale: Locale,
    zone: ZoneId,
    today: LocalDate,
    todayLabel: String,
    yesterdayLabel: String
): String {
    val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val time = dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return when (dateTime.toLocalDate()) {
        today -> "$todayLabel, $time"
        today.minusDays(1) -> "$yesterdayLabel, $time"
        else -> dateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    }
}

/** The crumbs of [path]: ("", rootLabel), ("a", "a"), ("a/b", "b"). */
internal fun breadcrumbs(path: String, rootLabel: String): List<Pair<String, String>> {
    val crumbs = mutableListOf("" to rootLabel)
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = if (current.isEmpty()) part else "$current/$part"
        crumbs += current to part
    }
    return crumbs
}
