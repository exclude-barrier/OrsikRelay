package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairResult
import io.github.excludebarrier.orsikrelay.domain.files.FileContent
import io.github.excludebarrier.orsikrelay.domain.files.FileContentKind
import io.github.excludebarrier.orsikrelay.domain.files.FileListing
import io.github.excludebarrier.orsikrelay.domain.files.FilesResult
import io.github.excludebarrier.orsikrelay.domain.files.FilesSource
import io.github.excludebarrier.orsikrelay.domain.files.ProjectListing
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient
import io.github.excludebarrier.orsikrelay.network.HermesFilesClient
import io.github.excludebarrier.orsikrelay.ui.files.FilesAskResult
import io.github.excludebarrier.orsikrelay.ui.files.FilesHermes
import io.github.excludebarrier.orsikrelay.ui.files.FilesRoute
import io.github.excludebarrier.orsikrelay.ui.files.FilesTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's files client and browser against a live bridge with
 * BRIDGE_FILES=1 (test stand folder: docs/notes.md, docs/bild.png,
 * docs/blob.bin, code/demo-app). Run like the other [LiveBridge] tests.
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeFilesE2ETest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    @Test
    fun browsesReadsAndListsProjectsOverTheTailnet() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue("liveBaseUrl/livePairingCode not provided", baseUrl != null && pairingCode != null)
        val paired = HermesDevicePairClient().pairDevice(baseUrl!!, pairingCode!!)
        assertTrue("expected Paired, got $paired", paired is DevicePairResult.Paired)
        val key = SecretValue((paired as DevicePairResult.Paired).credential.deviceKey.useRaw { it })
        val client = HermesFilesClient()
        assertTrue(client.supports(baseUrl, key))

        val text = client.read(baseUrl, key, "docs/notes.md")
        assertTrue("$text", text is FilesResult.Ready && text.value.kind == FileContentKind.Text)
        assertTrue((text as FilesResult.Ready).value.bytes.toString(Charsets.UTF_8).contains("äöü"))
        val image = client.read(baseUrl, key, "docs/bild.png")
        assertTrue("$image", image is FilesResult.Ready && image.value.kind == FileContentKind.Image)
        assertEquals(FilesResult.Refused, client.read(baseUrl, key, "etc-link/passwd"))
        assertEquals(FilesResult.NotFound, client.list(baseUrl, key, "missing"))
        val projects = client.projects(baseUrl, key)
        assertTrue("$projects", projects is FilesResult.Ready && projects.value.projects.any { it.name == "demo-app" && it.dirty == true })

        val source = object : FilesSource {
            override fun list(profileId: String, path: String): FilesResult<FileListing> = client.list(baseUrl, key, path)
            override fun read(profileId: String, path: String): FilesResult<FileContent> = client.read(baseUrl, key, path)
            override fun projects(profileId: String): FilesResult<ProjectListing> = client.projects(baseUrl, key)
        }
        composeRule.setContent {
            HermesTheme {
                FilesRoute(
                    profileId = "live", source = source, startPath = "", onBack = {},
                    hermes = FilesHermes(ask = { _, _ -> FilesAskResult.Failed }, openInSession = { _, _ -> false })
                )
            }
        }
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText("docs").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "docs").performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
        settle(3_000)
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "notes.md").performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText("Hallo vom Teststand.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // Markdown opens rendered.
        composeRule.onNodeWithTag(FilesTags.MARKDOWN).assertIsDisplayed()
        settle(3_000)
        composeRule.onNodeWithTag(FilesTags.MODE_PREFIX + "source").performClick()
        settle(3_000)
        composeRule.onNodeWithTag("files_viewer_back").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "bild.png").performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(FilesTags.IMAGE).fetchSemanticsNodes().isNotEmpty() }
        settle(3_000)
        composeRule.onNodeWithTag("files_viewer_back").performClick()
        composeRule.onNodeWithTag(FilesTags.ENTRY_PREFIX + "blob.bin").performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(FilesTags.BINARY).fetchSemanticsNodes().isNotEmpty() }
        settle(3_000)
        composeRule.onNodeWithTag("files_viewer_back").performClick()
        composeRule.onNodeWithTag(FilesTags.MORE_PREFIX + "notes.md").performClick()
        settle(3_000)
    }

    /** Lets frames run (room for a screenshot). */
    private fun settle(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
    }
}
