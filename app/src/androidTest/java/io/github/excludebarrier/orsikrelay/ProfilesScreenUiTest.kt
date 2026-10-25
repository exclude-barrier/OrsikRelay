package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.profiles.MANAGEMENT_TILE_PREFIX
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.activity.ComponentActivity
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.profiles.ProfilesScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.testdiagnostics.withUiFailureDiagnostics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The compact server list leaf contract:
 *
 * - non-secret credential chips ("Aktiv", "Gekoppelt", "API-Key"),
 * - tapping a non-active row selects it exactly once, the active row is not
 *   tappable,
 * - the ⋮ menu offers "Als aktiven Server verwenden" only for a non-active
 *   server and always opens the existing deletion dialog,
 * - every action keeps the 48 dp touch target at fontScale 2.0 and the row
 *   grows instead of clipping.
 *
 * All fakes are synthetic; no network request and no credential read is made.
 */
class ProfilesScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chipsShowActivePairedAndApiKeyKinds() {
        val admin = FakeProfileAdmin(
            profiles = listOf(
                profile(PAIRED_ID, "Paired", "https://paired.local"),
                profile(API_ID, "Api", "https://api.local"),
                profile(UNKNOWN_ID, "Unknown", "https://unknown.local")
            ),
            kinds = mapOf(
                PAIRED_ID to ProfileCredentialKind.DEVICE_CREDENTIAL,
                API_ID to ProfileCredentialKind.API_KEY
            ),
            failingKinds = setOf(UNKNOWN_ID)
        )
        setContent(admin, activeProfileId = PAIRED_ID)

        waitForText("Paired")
        assertEquals(1, count(profiles_chip_active()))
        assertEquals(1, count(profiles_chip_paired()))
        assertEquals(1, count(profiles_chip_api_key()))
        // The active, paired profile shows both chips.
        composeRule.onNodeWithTag(rowTag(PAIRED_ID)).assertIsDisplayed()
        composeRule.onNodeWithTag(rowTag(UNKNOWN_ID)).assertIsDisplayed()
    }

    @Test
    fun tappingANonActiveRowSelectsItExactlyOnce() {
        val admin = FakeProfileAdmin(
            profiles = listOf(
                profile(ALPHA_ID, "Alpha", "https://alpha.local"),
                profile(BETA_ID, "Beta", "https://beta.local")
            )
        )
        val selected = CopyOnWriteArrayList<String>()
        setContent(admin, activeProfileId = ALPHA_ID, onServerSelected = { selected += it })

        waitForText("Beta")
        composeRule.onNodeWithTag(rowTag(BETA_ID)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { selected.size == 1 }
        composeRule.waitForIdle()
        assertEquals(listOf(BETA_ID), selected)
        assertEquals(listOf(BETA_ID), admin.selected)
    }

    @Test
    fun tappingTheActiveRowDoesNothing() {
        val admin = FakeProfileAdmin(
            profiles = listOf(profile(ALPHA_ID, "Alpha", "https://alpha.local"))
        )
        val selected = CopyOnWriteArrayList<String>()
        setContent(admin, activeProfileId = ALPHA_ID, onServerSelected = { selected += it })

        waitForText("Alpha")
        composeRule.onNodeWithTag(rowTag(ALPHA_ID)).assertIsNotEnabled()
        composeRule.onNodeWithTag(rowTag(ALPHA_ID)).performClick()
        composeRule.waitForIdle()

        assertTrue(selected.isEmpty())
        assertTrue(admin.selected.isEmpty())
    }

    @Test
    fun theActiveRowsMenuDoesNotOfferTheSelectAction() {
        val admin = FakeProfileAdmin(
            profiles = listOf(profile(ALPHA_ID, "Alpha", "https://alpha.local"))
        )
        setContent(admin, activeProfileId = ALPHA_ID)

        waitForText("Alpha")
        composeRule.onNodeWithTag(actionsTag(ALPHA_ID)).performClick()
        composeRule.onNodeWithTag(selectTag(ALPHA_ID)).assertDoesNotExist()
        composeRule.onNodeWithTag(deleteTag(ALPHA_ID)).assertIsDisplayed()
    }

    @Test
    fun theNonActiveRowsMenuOffersSelectAndDeleteOpensTheExistingDialog() {
        val admin = FakeProfileAdmin(
            profiles = listOf(profile(BETA_ID, "Beta", "https://beta.local"))
        )
        setContent(admin, activeProfileId = ALPHA_ID)

        waitForText("Beta")
        composeRule.onNodeWithTag(actionsTag(BETA_ID)).performClick()
        composeRule.onNodeWithTag(selectTag(BETA_ID)).assertIsDisplayed()
        composeRule.onNodeWithTag(deleteTag(BETA_ID)).performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.profiles_delete_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.profiles_delete_message, "Beta"),
                substring = true
            )
            .assertIsDisplayed()
        composeRule.onNodeWithTag("profileDeleteConfirm").assertIsDisplayed()
    }

    @Test
    fun theRowGrowsAndKeepsTouchTargetsAtLargeFont() {
        val admin = FakeProfileAdmin(
            profiles = listOf(
                profile(
                    BETA_ID,
                    "Ein sehr langer Server-Name bei grosser Schrift",
                    "https://a-very-long-host-name.example.invalid/with/a/long/path"
                )
            )
        )
        renderAt(LARGE_FONT_SCALE) {
            ProfilesScreen(admin = admin, onBack = {}, activeProfileId = ALPHA_ID)
        }
        waitForText("Ein sehr langer Server-Name", substring = true)

        val row = composeRule.onNodeWithTag(rowTag(BETA_ID)).fetchSemanticsNode()
        val rowHeightDp = with(composeRule.density) {
            row.touchBoundsInRoot.height / density
        }
        assertTrue("row must be at least 64 dp tall, was $rowHeightDp", rowHeightDp >= 64f)

        val actions = composeRule.onNodeWithTag(actionsTag(BETA_ID)).fetchSemanticsNode()
        val actionsBounds = actions.touchBoundsInRoot
        assertTrue(
            "the ⋮ touch target must be at least 48 dp",
            with(composeRule.density) {
                actionsBounds.width / density >= 48f && actionsBounds.height / density >= 48f
            }
        )

        val add = composeRule.onNodeWithTag("addServerButton").fetchSemanticsNode()
        val addBounds = add.touchBoundsInRoot
        assertTrue(
            "the add-server touch target must be at least 48 dp",
            with(composeRule.density) {
                addBounds.width / density >= 48f && addBounds.height / density >= 48f
            }
        )
        composeRule.onNodeWithTag("addServerButton").assertIsDisplayed()
    }

    @Test
    fun asATabTheScreenHasNoBackArrowAndShowsTheRealReachability() {
        val admin = FakeProfileAdmin(
            profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local"), profile(API_ID, "Api", "https://api.local")),
            kinds = mapOf(PAIRED_ID to ProfileCredentialKind.DEVICE_CREDENTIAL, API_ID to ProfileCredentialKind.API_KEY)
        )
        val loadStartedAt = SystemClock.elapsedRealtime()
        withUiFailureDiagnostics(describe = {
            val stateAtFailure =
                "phase=initial profile rows; elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAt}; " +
                    "listCalls=${admin.listCalls.get()}; kindCalls=${admin.kindCalls.get()}; " +
                    "kindCompleted=${admin.kindCompleted.get()}"
            val tree = runCatching {
                composeRule.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }
            "$stateAtFailure\nui (collected after failure):\n$tree"
        }) {
            setContent(admin, activeProfileId = PAIRED_ID, onBack = null, activeReachable = true)
            composeRule.waitUntil(5_000) { count("Paired") > 0 }
        }

        composeRule.onAllNodesWithTag("settings_back").assertCountEquals(0)
        assertEquals(1, count(context.getString(R.string.home_server_reachable)))
        assertEquals(1, count(context.getString(R.string.profiles_status_not_connected)))
    }

    @Test
    fun anUnreachableActiveServerSaysSoAndAnUnknownStateShowsNothing() {
        val admin = FakeProfileAdmin(
            profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")),
            kinds = mapOf(PAIRED_ID to ProfileCredentialKind.DEVICE_CREDENTIAL)
        )
        setContent(admin, activeProfileId = PAIRED_ID, activeReachable = false)
        composeRule.waitUntil(5_000) { count("Paired") > 0 }

        assertEquals(1, count(context.getString(R.string.home_status_unreachable)))
        assertEquals(0, count(context.getString(R.string.home_server_reachable)))
    }

    @Test
    fun withActionsTheManagementToolsWorkMonitoringOpensHomeAndATaskStartsItsRequest() {
        val admin = FakeProfileAdmin(profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")), kinds = emptyMap())
        var monitoring = 0
        val started = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(
                    admin = admin,
                    onBack = {},
                    activeProfileId = PAIRED_ID,
                    onOpenMonitoring = { monitoring++ },
                    startTask = { title, prompt -> started += title to prompt; true }
                )
            }
        }
        composeRule.waitUntil(5_000) { count("Paired") > 0 }

        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "monitoring").performScrollTo().performClick()
        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "docker").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { started.isNotEmpty() }

        assertEquals(1, monitoring)
        assertEquals(
            listOf(context.getString(R.string.home_quick_docker) to context.getString(R.string.home_task_docker_prompt)),
            started.toList()
        )
    }

    @Test
    fun aFailedTaskSaysSo() {
        val admin = FakeProfileAdmin(profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")), kinds = emptyMap())
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(admin = admin, onBack = {}, activeProfileId = PAIRED_ID, onOpenMonitoring = {}, startTask = { _, _ -> false })
            }
        }
        composeRule.waitUntil(5_000) { count("Paired") > 0 }
        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "docker").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { count(context.getString(R.string.new_session_error_generic)) > 0 }
    }

    @Test
    fun aThrowingTaskShowsFailureAndStaysClickable() {
        val admin = FakeProfileAdmin(profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")), kinds = emptyMap())
        val calls = CopyOnWriteArrayList<Int>()
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(
                    admin = admin, onBack = {}, activeProfileId = PAIRED_ID, onOpenMonitoring = {},
                    startTask = { _, _ ->
                        calls += 1
                        if (calls.size == 1) throw RuntimeException("boom")
                        true
                    }
                )
            }
        }
        composeRule.waitUntil(5_000) { count("Paired") > 0 }

        // First click: start throws. The failure is surfaced, the screen stays
        // alive (no crash) and the tile must remain usable.
        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "docker").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { count(context.getString(R.string.new_session_error_generic)) > 0 }
        composeRule.waitForIdle()

        // busy was released by the finally block: a second click reaches start.
        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "docker").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(2, calls.size) }
    }

    @Test
    fun theTerminalTileOpensTheTerminalInsteadOfAChat() {
        val admin = FakeProfileAdmin(profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")), kinds = emptyMap())
        var terminalOpened = 0
        var tasksStarted = 0
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(
                    admin = admin, onBack = {}, activeProfileId = PAIRED_ID, onOpenMonitoring = {},
                    onOpenTool = { if (it == io.github.excludebarrier.orsikrelay.ui.home.QuickTask.Terminal) terminalOpened++ },
                    startTask = { _, _ -> tasksStarted++; true }
                )
            }
        }
        composeRule.waitUntil(5_000) { count("Paired") > 0 }
        composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + "terminal").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, terminalOpened)
            assertEquals(0, tasksStarted)
        }
    }

    @Test
    fun withoutActionsTheManagementToolsStayAPreview() {
        val admin = FakeProfileAdmin(profiles = listOf(profile(PAIRED_ID, "Paired", "https://paired.local")), kinds = emptyMap())
        setContent(admin, activeProfileId = PAIRED_ID)
        composeRule.waitUntil(5_000) { count("Paired") > 0 }

        listOf("monitoring", "docker", "terminal", "files").forEach { key ->
            composeRule.onNodeWithTag(MANAGEMENT_TILE_PREFIX + key)
                .performScrollTo()
                .assertIsNotEnabled()
                .assertHasNoClickAction()
        }
    }

    private fun setContent(
        admin: ProfileAdmin,
        activeProfileId: String?,
        onServerSelected: (String) -> Unit = {},
        onBack: (() -> Unit)? = {},
        activeReachable: Boolean? = null
    ) {
        composeRule.setContent {
            HermesTheme {
                ProfilesScreen(
                    admin = admin,
                    onBack = onBack,
                    activeProfileId = activeProfileId,
                    activeReachable = activeReachable,
                    onServerSelected = onServerSelected
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderAt(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                HermesTheme { content() }
            }
        }
    }

    private fun count(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    // Design v2 shows the badges in capitals.
    private fun badge(res: Int) = context.getString(res).uppercase(context.resources.configuration.locales[0])
    private fun profiles_chip_active() = badge(R.string.profiles_chip_active)
    private fun profiles_chip_paired() = badge(R.string.profiles_chip_paired)
    private fun profiles_chip_api_key() = badge(R.string.profiles_chip_api_key)

    private fun rowTag(id: String) = "serverRow:$id"
    private fun actionsTag(id: String) = "serverActions:$id"
    private fun selectTag(id: String) = "selectActiveServer:$id"
    private fun deleteTag(id: String) = "serverDelete:$id"

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun profile(id: String, name: String, baseUrl: String) =
        ServerProfile.create(id = id, profileName = name, baseUrl = baseUrl)

    private class FakeProfileAdmin(
        private val profiles: List<ServerProfile>,
        private val kinds: Map<String, ProfileCredentialKind> = emptyMap(),
        private val failingKinds: Set<String> = emptySet()
    ) : ProfileAdmin {
        val selected = CopyOnWriteArrayList<String>()
        val listCalls = AtomicInteger()
        val kindCalls = AtomicInteger()
        val kindCompleted = AtomicInteger()

        override fun listProfiles(): List<ServerProfile> {
            listCalls.incrementAndGet()
            return profiles
        }

        override fun deleteProfile(profileId: String) = Unit

        override fun selectActiveServer(profileId: String) {
            selected += profileId
        }

        override fun credentialKind(profileId: String): ProfileCredentialKind {
            kindCalls.incrementAndGet()
            check(profileId !in failingKinds) { "metadata unavailable" }
            return (kinds[profileId] ?: ProfileCredentialKind.API_KEY).also { kindCompleted.incrementAndGet() }
        }
    }

    private companion object {
        const val ALPHA_ID = "profile-a"
        const val BETA_ID = "profile-b"
        const val PAIRED_ID = "profile-paired"
        const val API_ID = "profile-api"
        const val UNKNOWN_ID = "profile-unknown"
        const val LARGE_FONT_SCALE = 2.0f
    }
}
