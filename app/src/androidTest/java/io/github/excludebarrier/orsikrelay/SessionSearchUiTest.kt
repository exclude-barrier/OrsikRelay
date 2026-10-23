package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The local title search of the session list: filtering is purely client
 * side over the already loaded sessions, the query survives recreation,
 * refresh and load-more, and no keystroke starts a request.
 */
class SessionSearchUiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typingFiltersRowsAndGroupHeaders() {
        setScreen(
            source = SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session(
                            "alpha",
                            "Alpha Session",
                            lastActive = epoch("2026-10-18T10:00:00Z")
                        ),
                        session(
                            "beta",
                            "Beta Session",
                            lastActive = epoch("2026-10-18T09:00:00Z")
                        ),
                        session(
                            "gamma",
                            "Gamma Session",
                            lastActive = epoch("2026-10-01T10:00:00Z")
                        )
                    ),
                    false
                )
            },
            clock = fixedClock()
        )
        waitForText("Alpha Session")
        waitForText(context.getString(R.string.sessions_group_older))

        searchField().performTextInput("alpha")

        waitForText("Alpha Session")
        composeRule.onAllNodesWithText("Beta Session").assertCountEquals(0)
        composeRule.onAllNodesWithText("Gamma Session").assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.sessions_group_today))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.sessions_group_older))
            .assertCountEquals(0)
    }

    @Test
    fun clearButtonClearsTheQueryAndShowsEverythingAgain() {
        setScreen(
            source = SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session("alpha", "Alpha Session"),
                        session("beta", "Beta Session")
                    ),
                    false
                )
            }
        )
        waitForText("Alpha Session")

        searchField().performTextInput("beta")
        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)

        val clear = composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_search_clear)
        )
        clear.assertIsDisplayed()
        clear.assertWidthIsAtLeast(48.dp)
        clear.assertHeightIsAtLeast(48.dp)

        clear.performClick()

        waitForText("Alpha Session")
        composeRule.onNodeWithText("Beta Session").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.sessions_search_clear))
            .assertCountEquals(0)
    }

    @Test
    fun zeroHitsShowsTheQueryInTheLocalizedEmptyText() {
        setScreen(
            source = SessionPageSource { _, _, _ ->
                success(listOf(session("alpha", "Alpha Session")), false)
            }
        )
        waitForText("Alpha Session")

        searchField().performTextInput("zzz")

        composeRule.onNodeWithTag("sessionsSearchNoResults")
            .assertIsDisplayed()
            .assertTextEquals(
                context.getString(R.string.sessions_search_no_results, "zzz")
            )
        composeRule.onAllNodesWithText(context.getString(R.string.sessions_empty))
            .assertCountEquals(0)
    }

    @Test
    fun hintAppearsWithFurtherPagesAndLoadMoreKeepsQueryAndFullOffset() {
        val offsets = CopyOnWriteArrayList<Int>()
        val hint = context.getString(R.string.sessions_search_only_loaded_hint)
        setScreen(
            source = SessionPageSource { _, _, offset ->
                offsets += offset
                if (offset == 0) {
                    success(
                        listOf(
                            session("alpha", "Alpha Session"),
                            session("beta", "Beta Session")
                        ),
                        true
                    )
                } else {
                    success(listOf(session("gamma", "Gamma Session")), false)
                }
            }
        )
        waitForText("Alpha Session")
        composeRule.onAllNodesWithText(hint).assertCountEquals(0)

        searchField().performTextInput("session")

        waitForText(hint)
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        assertEquals(listOf(0), offsets.toList())

        composeRule.onNodeWithText(context.getString(R.string.sessions_load_more))
            .performClick()

        waitForText("Gamma Session")
        assertEquals(listOf(0, 2), offsets.toList())
        composeRule.onNodeWithText("Alpha Session").assertIsDisplayed()
        composeRule.onNodeWithText("Beta Session").assertIsDisplayed()
        searchField().assert(hasText("session"))
        assertTrue(
            "hint must disappear when the last page is loaded",
            composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "load-more must disappear when the last page is loaded",
            composeRule.onAllNodesWithText(context.getString(R.string.sessions_load_more))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun zeroHitsKeepHintAndLoadMoreReachable() {
        val offsets = CopyOnWriteArrayList<Int>()
        val hint = context.getString(R.string.sessions_search_only_loaded_hint)
        setScreen(
            source = SessionPageSource { _, _, offset ->
                offsets += offset
                if (offset == 0) {
                    success(listOf(session("alpha", "Alpha Session")), true)
                } else {
                    success(emptyList(), false)
                }
            }
        )
        waitForText("Alpha Session")

        searchField().performTextInput("zzz")

        composeRule.onNodeWithTag("sessionsSearchNoResults").assertIsDisplayed()
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_load_more))
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { offsets.size == 2 }
        assertEquals(listOf(0, 1), offsets.toList())
    }

    @Test
    fun noHintWithoutFurtherPages() {
        val hint = context.getString(R.string.sessions_search_only_loaded_hint)
        setScreen(
            source = SessionPageSource { _, _, _ ->
                success(listOf(session("alpha", "Alpha Session")), false)
            }
        )
        waitForText("Alpha Session")

        searchField().performTextInput("alpha")

        composeRule.onAllNodesWithText(hint).assertCountEquals(0)
    }

    @Test
    fun refreshKeepsTheQueryAndTheFilter() {
        setScreen(
            source = SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session("alpha", "Alpha Session"),
                        session("beta", "Beta Session")
                    ),
                    false
                )
            }
        )
        waitForText("Alpha Session")

        searchField().performTextInput("beta")
        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)

        composeRule.refreshAgentsList()

        waitForText("Beta Session")
        searchField().assert(hasText("beta"))
        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)
    }

    @Test
    fun typingDoesNotRequestAnotherPage() {
        val calls = AtomicInteger()
        setScreen(
            source = SessionPageSource { _, _, _ ->
                calls.incrementAndGet()
                success(listOf(session("alpha", "Alpha Session")), false)
            }
        )
        waitForText("Alpha Session")
        assertEquals(1, calls.get())

        searchField().performTextInput("alpha")
        composeRule.waitForIdle()

        assertEquals(1, calls.get())
    }

    @Test
    fun querySurvivesRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesTheme {
                SessionsScreen(
                    pageSource = SessionPageSource { _, _, _ ->
                        success(
                            listOf(
                                session("alpha", "Alpha Session"),
                                session("beta", "Beta Session")
                            ),
                            false
                        )
                    },
                    profileId = PROFILE_ID
                )
            }
        }
        waitForText("Alpha Session")

        searchField().performTextInput("beta")
        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)

        restorationTester.emulateSavedInstanceStateRestore()

        waitForText("Beta Session")
        searchField().assert(hasText("beta"))
        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)
    }

    private fun setScreen(
        source: SessionPageSource,
        clock: Clock = Clock.systemDefaultZone()
    ) {
        composeRule.setContent {
            HermesTheme {
                SessionsScreen(
                    pageSource = source,
                    profileId = PROFILE_ID,
                    clock = clock
                )
            }
        }
    }

    @Test
    fun noSearchFieldOnAnError() {
        setScreen(
            source = SessionPageSource { _, _, _ ->
                SessionListResult.Failure(SessionListError.Network)
            }
        )
        waitForText(context.getString(R.string.error_session_generic))
        searchField().assertDoesNotExist()
    }

    @Test
    fun noSearchFieldForAnEmptyList() {
        setScreen(
            source = SessionPageSource { _, _, _ -> success(emptyList(), false) }
        )
        waitForText(context.getString(R.string.sessions_empty))
        searchField().assertDoesNotExist()
    }

    private fun searchField() = composeRule.onNodeWithTag("sessionsSearchField")

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun success(
        sessions: List<HermesSession>,
        hasMore: Boolean
    ) = SessionListResult.Success(SessionPage(sessions, hasMore))

    private fun session(
        id: String,
        title: String?,
        lastActive: Long? = null
    ) = HermesSession(
        id = id,
        title = title,
        source = null,
        lastActiveEpochSeconds = lastActive,
        messageCount = null,
        pinned = false
    )

    private fun epoch(instant: String): Long = Instant.parse(instant).epochSecond

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
