package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.components.TAG_LOADING_INDICATOR
import io.github.excludebarrier.orsikrelay.ui.sessions.AGENT_FILTER_PREVIEW_PREFIX
import io.github.excludebarrier.orsikrelay.ui.sessions.AGENT_FILTER_HERMES_TAG
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionListFormatter
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionsScreenUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingIsShownWhileInitialReadIsInFlight() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        setScreen(
            SessionPageSource { _, _, _ ->
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                success(emptyList(), false)
            }
        )

        assertTrue(started.await(3, TimeUnit.SECONDS))
        composeRule.onNodeWithText(context.getString(R.string.sessions_loading)).assertIsDisplayed()
        release.countDown()
        waitForText(context.getString(R.string.sessions_empty))
    }

    @Test
    fun realListAndFallbackMetadataAreRendered() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session(
                            id = "fixture-a",
                            title = "Erste Fixture-Session",
                            source = "cli",
                            messageCount = 7,
                            pinned = true
                        ),
                        session(id = "fixture-b", title = null)
                    ),
                    false
                )
            }
        )

        waitForText("Erste Fixture-Session")
        composeRule.onNodeWithText(context.getString(R.string.sessions_group_pinned))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Erste Fixture-Session")
            .assert(hasText(context.getString(R.string.session_source_terminal), substring = true))
        val messageCount = context.resources.getQuantityString(
            R.plurals.session_message_count,
            7,
            "7"
        )
        composeRule.onNodeWithText(
            "${context.getString(R.string.session_source_terminal)} · $messageCount",
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.session_fallback_title)).assertIsDisplayed()
    }

    @Test
    fun emptyStateOnlyFollowsSuccessfulEmptyPage() {
        setScreen(SessionPageSource { _, _, _ -> success(emptyList(), false) })

        waitForText(context.getString(R.string.sessions_empty))
        composeRule.onNodeWithText(context.getString(R.string.sessions_empty)).assertIsDisplayed()
    }

    @Test
    fun groupHeadersFollowTheFixedOrderAndAreHeadings() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session("older", "Ältere Session", lastActive = epoch("2026-10-01T10:00:00Z")),
                        session("today", "Heutige Session", lastActive = epoch("2026-10-18T10:00:00Z")),
                        session(
                            "pinned",
                            "Angepinnte Session",
                            lastActive = epoch("2020-01-01T10:00:00Z"),
                            pinned = true
                        ),
                        session("recent", "Letzte Woche", lastActive = epoch("2026-10-12T10:00:00Z")),
                        session("yesterday", "Gestrige Session", lastActive = epoch("2026-10-17T10:00:00Z"))
                    ),
                    false
                )
            },
            clock = fixedClock()
        )

        waitForText("Angepinnte Session")
        val headers = listOf(
            R.string.sessions_group_pinned,
            R.string.sessions_group_today,
            R.string.sessions_group_yesterday,
            R.string.sessions_group_last_7_days,
            R.string.sessions_group_older
        ).map { context.getString(it) }
        headers.forEach { header -> waitForText(header) }

        val tops = headers.map { header ->
            composeRule.onNodeWithText(header).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        composeRule.onNodeWithText(context.getString(R.string.sessions_group_today))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun onlyNonEmptyGroupHeadersAreShown() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(session("today", "Nur heute", lastActive = epoch("2026-10-18T10:00:00Z"))),
                    false
                )
            },
            clock = fixedClock()
        )

        waitForText(context.getString(R.string.sessions_group_today))
        listOf(
            R.string.sessions_group_pinned,
            R.string.sessions_group_yesterday,
            R.string.sessions_group_last_7_days,
            R.string.sessions_group_older
        ).forEach { resource ->
            composeRule.onAllNodesWithText(context.getString(resource)).assertCountEquals(0)
        }
    }

    @Test
    fun rowIsOneClickTargetWithMinimumHeightAndSourceName() {
        var opened = 0
        var openedId: String? = null
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session(
                            id = "fixture-row",
                            title = "Telegram-Session",
                            source = "telegram",
                            messageCount = 12,
                            lastActive = epoch("2026-10-18T10:00:00Z")
                        )
                    ),
                    false
                )
            },
            clock = fixedClock(),
            onSessionSelected = { id, _ ->
                opened++
                openedId = id
            }
        )

        waitForText("Telegram-Session")
        val row = composeRule.onNode(hasText("Telegram-Session") and hasClickAction())
        row.assertIsDisplayed()
        row.assertHeightIsAtLeast(64.dp)
        val sourceLabel = context.getString(R.string.session_source_telegram)
        val messageCount = context.resources.getQuantityString(
            R.plurals.session_message_count,
            12,
            "12"
        )
        val lastActive = checkNotNull(
            SessionListFormatter.formatLastActiveTime(
                epoch("2026-10-18T10:00:00Z"),
                context.resources.configuration.locales[0],
                ZoneOffset.UTC
            )
        )
        composeRule.onNodeWithText(
            "$sourceLabel · $messageCount · $lastActive",
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(sourceLabel, useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodes(hasText("Telegram-Session") and hasClickAction())
            .assertCountEquals(1)

        row.performClick()
        assertEquals(1, opened)
        assertEquals("fixture-row", openedId)
    }

    @Test
    fun unknownSourceUsesRawNameAndMissingSourceHasNoPrefix() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session(
                            id = "fixture-other",
                            title = "Matrix-Session",
                            source = "matrix",
                            messageCount = 5
                        ),
                        session(
                            id = "fixture-missing",
                            title = "Session ohne Quelle",
                            messageCount = 5
                        )
                    ),
                    false
                )
            }
        )

        waitForText("Matrix-Session")
        val messageCount = context.resources.getQuantityString(
            R.plurals.session_message_count,
            5,
            "5"
        )
        composeRule.onNodeWithText("matrix · $messageCount", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(messageCount, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun missingProfileShowsActionableHint() {
        setScreen(SessionPageSource { _, _, _ -> SessionListResult.Failure(SessionListError.MissingProfile) })

        waitForText(context.getString(R.string.error_session_profile))
        composeRule.onNodeWithTag("sessionsErrorHint")
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.sessions_error_hint_missing_profile))
    }

    @Test
    fun networkFailureShowsReachabilityHint() {
        setScreen(SessionPageSource { _, _, _ -> SessionListResult.Failure(SessionListError.Network) })

        waitForText(context.getString(R.string.error_session_generic))
        composeRule.onNodeWithTag("sessionsErrorHint")
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.sessions_error_hint_unreachable))
    }

    @Test
    fun httpFailureShowsNoHint() {
        setScreen(SessionPageSource { _, _, _ -> SessionListResult.Failure(SessionListError.Http) })

        waitForText(context.getString(R.string.error_session_generic))
        composeRule.onAllNodesWithTag("sessionsErrorHint").assertCountEquals(0)
    }

    @Test
    fun failureIsGenericAndRetryIsManual() {
        val calls = AtomicInteger()
        setScreen(
            SessionPageSource { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    SessionListResult.Failure(SessionListError.Network)
                } else {
                    success(listOf(session("fixture-a", "Nach Retry")), false)
                }
            }
        )

        waitForText(context.getString(R.string.error_session_generic))
        assertEquals(1, calls.get())
        composeRule.onNodeWithText(context.getString(R.string.sessions_retry)).performClick()
        waitForText("Nach Retry")
        assertEquals(2, calls.get())
    }

    @Test
    fun newSessionFabOpensTheDialog() {
        setScreen(
            source = SessionPageSource { _, _, _ -> success(emptyList(), false) },
            creator = SessionCreator { _, title -> SessionCreateResult.Success("created-id", title) }
        )

        val fab = composeRule.onNodeWithTag("sessionsNewSessionFab")
        fab.assertIsDisplayed()
        fab.assertWidthIsAtLeast(48.dp)
        fab.assertHeightIsAtLeast(48.dp)

        fab.performClick()

        composeRule.onNodeWithText(context.getString(R.string.new_session_title_label))
            .assertIsDisplayed()
    }

    @Test
    fun newSessionFabIsAbsentWithoutCreator() {
        setScreen(SessionPageSource { _, _, _ -> success(emptyList(), false) })

        composeRule.onNodeWithTag("sessionsNewSessionFab").assertDoesNotExist()
    }

    @Test
    fun pullToRefreshReloadsTheListExactlyOnce() {
        val calls = AtomicInteger()
        setScreen(
            SessionPageSource { _, _, _ ->
                calls.incrementAndGet()
                success(listOf(session("fixture-a", "Ziehbar")), false)
            }
        )
        waitForText("Ziehbar")
        assertEquals(1, calls.get())

        composeRule.onNodeWithTag("sessionsPullToRefresh").performTouchInput { swipeDown() }

        composeRule.waitUntil(timeoutMillis = 5_000) { calls.get() == 2 }
        composeRule.waitForIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun pullToRefreshWorksOnTheEmptyState() {
        val calls = AtomicInteger()
        setScreen(
            SessionPageSource { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    success(emptyList(), false)
                } else {
                    success(listOf(session("fixture-a", "Nach Leer-Refresh")), false)
                }
            }
        )
        waitForText(context.getString(R.string.sessions_empty))

        composeRule.onNodeWithTag("sessionsPullToRefresh").performTouchInput { swipeDown() }

        waitForText("Nach Leer-Refresh")
        assertEquals(2, calls.get())
    }

    @Test
    fun pullToRefreshWorksInTheErrorStateAndKeepsTheRetryButton() {
        val calls = AtomicInteger()
        setScreen(
            SessionPageSource { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    SessionListResult.Failure(SessionListError.Network)
                } else {
                    success(listOf(session("fixture-a", "Nach Fehler-Refresh")), false)
                }
            }
        )
        waitForText(context.getString(R.string.error_session_generic))
        composeRule.onNodeWithText(context.getString(R.string.sessions_retry))
            .assertIsDisplayed()

        composeRule.onNodeWithTag("sessionsPullToRefresh").performTouchInput { swipeDown() }

        waitForText("Nach Fehler-Refresh")
        assertEquals(2, calls.get())
    }

    @Test
    fun overflowRefreshActionReloadsTheListExactlyOnce() {
        val calls = AtomicInteger()
        setScreen(
            SessionPageSource { _, _, _ ->
                calls.incrementAndGet()
                success(listOf(session("fixture-a", "Menü-Refresh")), false)
            }
        )
        waitForText("Menü-Refresh")
        assertEquals(1, calls.get())

        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_refresh").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { calls.get() == 2 }
        assertEquals(2, calls.get())
    }

    @Test
    fun searchStillFiltersTheLoadedSessions() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(
                    listOf(
                        session("fixture-a", "Alpha Session"),
                        session("fixture-b", "Beta Session")
                    ),
                    false
                )
            }
        )
        waitForText("Alpha Session")

        composeRule.onNodeWithTag("sessionsSearchField").performTextInput("beta")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alpha Session").assertCountEquals(0)
        composeRule.onNodeWithText("Beta Session").assertIsDisplayed()
    }

    @Test
    fun loadMoreUsesAccumulatedOffsetAndKeepsServerOrder() {
        val offsets = mutableListOf<Int>()
        setScreen(
            SessionPageSource { _, _, offset ->
                synchronized(offsets) { offsets += offset }
                if (offset == 0) {
                    success(listOf(session("fixture-a", "Seite Eins")), true)
                } else {
                    success(listOf(session("fixture-b", "Seite Zwei")), false)
                }
            }
        )

        waitForText(context.getString(R.string.sessions_load_more))
        composeRule.onNodeWithText(context.getString(R.string.sessions_load_more)).performClick()
        waitForText("Seite Zwei")

        composeRule.onNodeWithText("Seite Eins").assertIsDisplayed()
        composeRule.onNodeWithText("Seite Zwei").assertIsDisplayed()
        assertEquals(listOf(0, 1), synchronized(offsets) { offsets.toList() })
    }

    @Test
    fun doubleTapWhileLoadingDoesNotStartParallelRead() {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        setScreen(
            SessionPageSource { _, _, _ ->
                calls.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                active.decrementAndGet()
                success(emptyList(), false)
            }
        )

        assertTrue(started.await(3, TimeUnit.SECONDS))
        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_refresh").performClick()
        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_refresh").performClick()
        assertEquals(1, calls.get())
        assertEquals(1, maxActive.get())
        release.countDown()
        waitForText(context.getString(R.string.sessions_empty))
    }

    @Test
    fun overflowMenuButtonIsVisibleWithAccessibleLabelAndTouchTarget() {
        setScreen(SessionPageSource { _, _, _ -> success(emptyList(), false) })

        val button = composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_more_options)
        )
        button.assertIsDisplayed()
        button.assertWidthIsAtLeast(48.dp)
        button.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun overflowMenuShowsExactlyTheFourEntriesInOrder() {
        setScreen(SessionPageSource { _, _, _ -> success(emptyList(), false) })

        openOverflowMenu()

        composeRule.onNodeWithTag("sessions_overflow_menu")
            .onChildren()
            .assertCountEquals(4)
        val tags = listOf(
            "sessions_menu_refresh",
            "sessions_menu_manage_servers",
            "sessions_menu_settings",
            "sessions_menu_about"
        )
        tags.forEach { tag -> composeRule.onNodeWithTag(tag).assertIsDisplayed() }
        val tops = tags.map { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun everyOverflowEntryInvokesOnlyItsCallbackOnceAndClosesTheMenu() {
        var manageServers = 0
        var settings = 0
        var about = 0
        setScreen(
            source = SessionPageSource { _, _, _ -> success(emptyList(), false) },
            onOpenProfileManagement = { manageServers++ },
            onOpenSettings = { settings++ },
            onOpenAbout = { about++ }
        )

        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_manage_servers").performClick()
        assertEquals(1, manageServers)
        assertEquals(0, settings)
        assertEquals(0, about)
        assertMenuClosed()

        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_settings").performClick()
        assertEquals(1, manageServers)
        assertEquals(1, settings)
        assertEquals(0, about)
        assertMenuClosed()

        openOverflowMenu()
        composeRule.onNodeWithTag("sessions_menu_about").performClick()
        assertEquals(1, manageServers)
        assertEquals(1, settings)
        assertEquals(1, about)
        assertMenuClosed()
    }

    private fun openOverflowMenu() {
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_more_options)
        ).performClick()
        composeRule.waitForIdle()
    }

    private fun assertMenuClosed() {
        composeRule.waitForIdle()
        assertTrue(
            "overflow menu must be closed",
            composeRule.onAllNodesWithTag("sessions_overflow_menu")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun onlyHermesCanBeFilteredTheOtherAgentsAreComingSoon() {
        setScreen(SessionPageSource { _, _, _ -> success(listOf(session(id = "fixture-a", title = "Fixture A")), false) })
        waitForText("Fixture A")

        composeRule.onNodeWithTag(AGENT_FILTER_HERMES_TAG).assertIsSelected()
        listOf("claude_code", "codex").forEach { key ->
            composeRule.onNodeWithTag(AGENT_FILTER_PREVIEW_PREFIX + key)
                .assertIsNotEnabled()
                .assertHasNoClickAction()
        }
    }

    @Test
    fun theRunningSessionShowsTheLoadingStarAndRunningLabel() {
        setScreen(
            SessionPageSource { _, _, _ ->
                success(listOf(session(id = "fixture-run", title = "Fixture Run"), session(id = "fixture-idle", title = "Fixture Idle")), false)
            },
            runningSessionId = "fixture-run"
        )
        waitForText("Fixture Run")

        composeRule.onNodeWithText("Fixture Run").assert(hasText(context.getString(R.string.sessions_running), substring = true))
        composeRule.onNodeWithText("Fixture Idle").assert(hasText(context.getString(R.string.sessions_running), substring = true).not())
        composeRule.onAllNodesWithTag(TAG_LOADING_INDICATOR, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun setScreen(
        source: SessionPageSource,
        onOpenProfileManagement: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onSessionSelected: (sessionId: String, title: String?) -> Unit = { _, _ -> },
        clock: Clock = Clock.systemDefaultZone(),
        creator: SessionCreator? = null,
        runningSessionId: String? = null
    ) {
        composeRule.setContent {
            HermesTheme {
                SessionsScreen(
                    pageSource = source,
                    profileId = PROFILE_ID,
                    creator = creator,
                    onSessionSelected = onSessionSelected,
                    onOpenProfileManagement = onOpenProfileManagement,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                    runningSessionId = runningSessionId,
                    clock = clock
                )
            }
        }
    }

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
        source: String? = null,
        messageCount: Long? = null,
        pinned: Boolean = false,
        lastActive: Long? = null
    ) = HermesSession(
        id = id,
        title = title,
        source = source,
        lastActiveEpochSeconds = lastActive,
        messageCount = messageCount,
        pinned = pinned
    )

    private fun epoch(instant: String): Long = Instant.parse(instant).epochSecond

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
