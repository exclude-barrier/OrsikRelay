package io.github.excludebarrier.orsikrelay.ui.files

import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectCommit
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectsModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(text: String): Long = LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC)
    private val now = at("2026-11-01T15:00:00")

    private fun project(name: String, path: String = "code/$name", dirty: Boolean? = false, commitAt: Long? = null) =
        Project(name, path, "main", dirty, commitAt?.let { ProjectCommit("abc1234", it, "msg") })

    @Test
    fun recentPutsTheNewestCommitFirstAndProjectsWithoutCommitLast() {
        val list = listOf(
            project("b", commitAt = at("2026-10-01T10:00:00")),
            project("none"),
            project("a", commitAt = at("2026-11-01T14:00:00"))
        )

        assertEquals(listOf("a", "b", "none"), arrangeProjects(list, ProjectSort.Recent, "", false).map { it.name })
    }

    @Test
    fun nameSortsCaseInsensitively() {
        val list = listOf(project("website"), project("Dotfiles"), project("ansible"))

        assertEquals(listOf("ansible", "Dotfiles", "website"), arrangeProjects(list, ProjectSort.Name, "", false).map { it.name })
    }

    @Test
    fun theQueryMatchesNameOrPath() {
        val list = listOf(project("orsik-relay", path = "code/orsik-relay"), project("notes", path = "srv/homelab/notes"))

        assertEquals(listOf("orsik-relay"), arrangeProjects(list, ProjectSort.Name, "RELAY", false).map { it.name })
        assertEquals(listOf("notes"), arrangeProjects(list, ProjectSort.Name, "homelab", false).map { it.name })
    }

    @Test
    fun changedOnlyKeepsProjectsGitReportsAsChanged() {
        val list = listOf(project("a", dirty = true), project("b", dirty = false), project("c", dirty = null))

        assertEquals(listOf("a"), arrangeProjects(list, ProjectSort.Name, "", true).map { it.name })
    }

    @Test
    fun agesFollowTheListWording() {
        assertEquals(CommitAge.Minutes(1), commitAge(now, now - 20, zone))
        assertEquals(CommitAge.Minutes(42), commitAge(now, now - 42 * 60, zone))
        assertEquals(CommitAge.Hours(3), commitAge(now, at("2026-11-01T11:30:00"), zone))
        assertEquals(CommitAge.Yesterday, commitAge(now, at("2026-10-31T23:30:00"), zone))
        assertEquals(CommitAge.Days(2), commitAge(now, at("2026-10-30T08:00:00"), zone))
        assertEquals(CommitAge.Days(6), commitAge(now, at("2026-10-26T08:00:00"), zone))
        assertEquals(CommitAge.Date(20, 10), commitAge(now, at("2026-10-20T08:00:00"), zone))
    }

    @Test
    fun aCommitFromTheFutureCountsAsOneMinute() {
        assertEquals(CommitAge.Minutes(1), commitAge(now, now + 600, zone))
    }

    @Test
    fun plainPathsStayAndOthersAreSingleQuoted() {
        assertEquals("/srv/code/orsik-relay", shellQuote("/srv/code/orsik-relay"))
        assertEquals("'/srv/my code'", shellQuote("/srv/my code"))
        assertEquals("'/srv/it'\\''s; rm -rf ~'", shellQuote("/srv/it's; rm -rf ~"))
        assertEquals("'/srv/\$(id)'", shellQuote("/srv/\$(id)"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun theTerminalCommandChangesIntoTheProjectAndShowsItsStatus() {
        assertEquals("cd /srv/code/app && git status", projectTerminalCommand("/srv/code/app"))
        assertEquals("cd '/srv/a b' && git status", projectTerminalCommand("/srv/a b"))
    }
}
