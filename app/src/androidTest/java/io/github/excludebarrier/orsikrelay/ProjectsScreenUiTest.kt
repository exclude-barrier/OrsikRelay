package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.Project
import io.github.excludebarrier.orsikrelay.domain.files.ProjectCommit
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.ui.components.TAG_WORKING_GLYPH
import io.github.excludebarrier.orsikrelay.ui.files.FilesAskResult
import io.github.excludebarrier.orsikrelay.ui.files.FilesHermes
import io.github.excludebarrier.orsikrelay.ui.files.ProjectsRoute
import io.github.excludebarrier.orsikrelay.ui.files.ProjectsTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Package 7: the project list, its filters, states and the ⋮ sheet. */
class ProjectsScreenUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_800_000_000L

    private val projects = listOf(
        Project("orsik-relay", "code/orsik-relay", "feature/projects", true, ProjectCommit("a1c9e02", now - 300, "Projects screen")),
        Project("website", "code/website", "main", false, ProjectCommit("b2d0f13", now - 3 * 86_400, "Update imprint")),
        Project("nas-config", "nas-config", "main", null, null)
    )

    private class FakeProjects(
        var result: () -> FilesResult<ProjectListing>,
        val root: String? = "/srv/code"
    ) : FilesSource {
        val projectCalls = AtomicInteger()
        override fun list(profileId: String, path: String): FilesResult<FileListing> =
            FilesResult.Ready(FileListing(path, emptyList(), false, root))
        override fun read(profileId: String, path: String): FilesResult<FileContent> = FilesResult.Failed
        override fun projects(profileId: String): FilesResult<ProjectListing> {
            projectCalls.incrementAndGet()
            return result()
        }
    }

    private val opened = CopyOnWriteArrayList<String>()
    private val terminal = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<Pair<String, String>>()
    private val hermes = FilesHermes(
        ask = { path, question -> asked += path to question; FilesAskResult.Opened },
        openInSession = { _, _ -> true }
    )

    private fun show(source: FilesSource) {
        composeRule.setContent {
            HermesTheme {
                ProjectsRoute(
                    profileId = "p1",
                    source = source,
                    onOpenProject = { opened += it },
                    onBack = {},
                    hermes = hermes,
                    onOpenTerminal = { terminal += it },
                    nowSeconds = { now }
                )
            }
        }
    }

    private fun ready(list: List<Project> = projects, truncated: Boolean = false) =
        FakeProjects({ FilesResult.Ready(ProjectListing(list, truncated)) })

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theHeadCountsProjectsAndChangesAndRowsShowTheirState() {
        show(ready())
        waitForTag(ProjectsTags.SUMMARY)

        val summary = context.resources.getQuantityString(R.plurals.projects_count, 3, 3) +
            " · " + context.getString(R.string.projects_changed_count, 1)
        composeRule.onNodeWithText(summary).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.projects_changed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.projects_clean)).assertIsDisplayed()
        composeRule.onNodeWithText("feature/projects").assertIsDisplayed()
        composeRule.onNodeWithText("Projects screen", substring = true).assertIsDisplayed()
        // No readable commit and no git state: the hint, and no chip.
        composeRule.onNodeWithText(context.getString(R.string.projects_no_commit), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.projects_only_changed, 1)).assertIsDisplayed()
    }

    @Test
    fun searchFiltersAndSaysWhenNothingMatches() {
        show(ready())
        waitForTag(ProjectsTags.SEARCH)

        composeRule.onNodeWithTag(ProjectsTags.SEARCH).performTextInput("web")
        composeRule.onNodeWithText("website").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag(ProjectsTags.PROJECT_PREFIX + "code/orsik-relay").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag(ProjectsTags.SEARCH).performTextInput("xyz")
        composeRule.onNodeWithTag(ProjectsTags.NO_MATCH).assertIsDisplayed()
    }

    @Test
    fun onlyChangedKeepsChangedProjects() {
        show(ready())
        waitForTag(ProjectsTags.ONLY_CHANGED)

        composeRule.onNodeWithTag(ProjectsTags.ONLY_CHANGED).performClick()

        composeRule.onNodeWithTag(ProjectsTags.PROJECT_PREFIX + "code/orsik-relay").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag(ProjectsTags.PROJECT_PREFIX + "code/website").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingAProjectOpensItsFolder() {
        show(ready())
        waitForTag(ProjectsTags.PROJECT_PREFIX + "code/website")

        composeRule.onNodeWithTag(ProjectsTags.PROJECT_PREFIX + "code/website").performClick()

        composeRule.runOnIdle { assertEquals(listOf("code/website"), opened.toList()) }
    }

    @Test
    fun theSheetOffersFourActionsAndTheTerminalGetsACommandItDoesNotRun() {
        show(ready())
        waitForTag(ProjectsTags.MORE_PREFIX + "code/orsik-relay")

        composeRule.onNodeWithContentDescription(context.getString(R.string.projects_actions, "orsik-relay")).performClick()
        // The terminal row appears once the absolute folder is known.
        waitForTag(ProjectsTags.ACTION_TERMINAL)
        composeRule.onNodeWithText("/srv/code/code/orsik-relay · feature/projects · a1c9e02").assertIsDisplayed()
        listOf(ProjectsTags.ACTION_FILES, ProjectsTags.ACTION_ASK, ProjectsTags.ACTION_COPY).forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed()
        }

        composeRule.onNodeWithTag(ProjectsTags.ACTION_TERMINAL).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("cd /srv/code/code/orsik-relay && git status"), terminal.toList())
        }
    }

    @Test
    fun askingHermesNamesPathAndBranch() {
        show(ready())
        waitForTag(ProjectsTags.MORE_PREFIX + "code/orsik-relay")
        composeRule.onNodeWithTag(ProjectsTags.MORE_PREFIX + "code/orsik-relay").performClick()
        waitForTag(ProjectsTags.ACTION_TERMINAL)

        composeRule.onNodeWithTag(ProjectsTags.ACTION_ASK).performClick()

        composeRule.waitUntil(5_000) { asked.isNotEmpty() }
        assertEquals("/srv/code/code/orsik-relay", asked.single().first)
        assertEquals(context.getString(R.string.projects_ask_question_branch, "feature/projects"), asked.single().second)
    }

    @Test
    fun withoutAKnownRootTheTerminalIsNotOffered() {
        val source = FakeProjects({ FilesResult.Ready(ProjectListing(projects, false)) }, root = null)
        show(source)
        waitForTag(ProjectsTags.MORE_PREFIX + "code/website")
        composeRule.onNodeWithTag(ProjectsTags.MORE_PREFIX + "code/website").performClick()
        waitForTag(ProjectsTags.ACTION_COPY)
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithTag(ProjectsTags.ACTION_TERMINAL).fetchSemanticsNodes().size)
    }

    @Test
    fun loadingShowsTheRelayWithItsText() {
        val release = CountDownLatch(1)
        show(FakeProjects({ release.await(5, TimeUnit.SECONDS); FilesResult.Ready(ProjectListing(projects, false)) }))

        waitForTag(ProjectsTags.LOADING)
        composeRule.onNodeWithText(context.getString(R.string.projects_loading)).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_WORKING_GLYPH, useUnmergedTree = true).assertIsDisplayed()
        release.countDown()
    }

    @Test
    fun anEmptyListExplainsTheSearchDepth() {
        show(ready(emptyList()))
        waitForTag(ProjectsTags.EMPTY)

        composeRule.onNodeWithText(context.getString(R.string.projects_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.projects_empty_depth)).assertIsDisplayed()
    }

    @Test
    fun aFailureOffersRetry() {
        var fail = true
        val source = FakeProjects({ if (fail) FilesResult.Failed else FilesResult.Ready(ProjectListing(projects, false)) })
        show(source)
        waitForTag(ProjectsTags.FAILED)
        composeRule.onNodeWithText(context.getString(R.string.projects_failed)).assertIsDisplayed()

        fail = false
        composeRule.onNodeWithTag(ProjectsTags.RETRY).performClick()

        waitForTag(ProjectsTags.SUMMARY)
        assertTrue(source.projectCalls.get() >= 2)
    }

    @Test
    fun aTruncatedListSaysSo() {
        show(ready(truncated = true))
        waitForTag(ProjectsTags.SUMMARY)

        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag(ProjectsTags.TRUNCATED))
        composeRule.onNodeWithTag(ProjectsTags.TRUNCATED).assertIsDisplayed()
    }
}
