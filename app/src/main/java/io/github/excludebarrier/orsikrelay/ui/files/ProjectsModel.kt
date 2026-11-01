package io.github.excludebarrier.orsikrelay.ui.files

import io.github.excludebarrier.orsikrelay.domain.files.Project
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Order of the project list: newest commit first (default) or by name. */
internal enum class ProjectSort { Recent, Name }

/**
 * The visible projects: [query] matches name or path (case-insensitive),
 * [changedOnly] keeps projects git reports as changed. Recent puts projects
 * without a readable commit last; ties and Name sort by name.
 */
internal fun arrangeProjects(
    projects: List<Project>,
    sort: ProjectSort,
    query: String,
    changedOnly: Boolean
): List<Project> {
    val needle = query.trim().lowercase()
    val byName = compareBy<Project> { it.name.lowercase() }.thenBy { it.path }
    val comparator = when (sort) {
        ProjectSort.Name -> byName
        ProjectSort.Recent -> compareByDescending<Project> { it.lastCommit?.committedAtSeconds ?: Long.MIN_VALUE }
            .then(byName)
    }
    return projects
        .filter { !changedOnly || it.dirty == true }
        .filter { needle.isEmpty() || needle in it.name.lowercase() || needle in it.path.lowercase() }
        .sortedWith(comparator)
}

/** A commit's age as the list shows it. */
internal sealed interface CommitAge {
    data class Minutes(val count: Long) : CommitAge
    data class Hours(val count: Long) : CommitAge
    data object Yesterday : CommitAge
    data class Days(val count: Long) : CommitAge
    data class Date(val day: Int, val month: Int) : CommitAge
}

/**
 * "vor X Min." below an hour, "vor X Std." the same day, "gestern" the
 * calendar day before, "vor N Tagen" up to six days, otherwise the date.
 * A commit from the future (clock skew) counts as one minute old.
 */
internal fun commitAge(nowSeconds: Long, thenSeconds: Long, zone: ZoneId): CommitAge {
    val seconds = (nowSeconds - thenSeconds).coerceAtLeast(0)
    if (seconds < 3_600) return CommitAge.Minutes((seconds / 60).coerceAtLeast(1))
    val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
    val then = Instant.ofEpochSecond(thenSeconds).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0 -> CommitAge.Hours(seconds / 3_600)
        days == 1L -> CommitAge.Yesterday
        days <= 6 -> CommitAge.Days(days)
        else -> CommitAge.Date(then.dayOfMonth, then.monthValue)
    }
}

private val SHELL_SAFE = Regex("[A-Za-z0-9_./@%+=:,-]+")

/** [value] as one shell word: unchanged when it is plain, else single-quoted. */
internal fun shellQuote(value: String): String =
    if (value.isNotEmpty() && SHELL_SAFE.matches(value)) value else "'" + value.replace("'", "'\\''") + "'"

/** What "Im Terminal öffnen" types into the terminal input; the user runs it with Enter. */
internal fun projectTerminalCommand(serverPath: String): String = "cd ${shellQuote(serverPath)} && git status"
