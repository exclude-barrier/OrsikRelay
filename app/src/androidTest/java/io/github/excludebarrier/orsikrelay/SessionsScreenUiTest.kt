package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
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
        composeRule.onNodeWithText("cli · " + context.getString(R.string.session_pinned)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.session_message_count,
                7,
                "7"
            )
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
        composeRule.onNodeWithText(context.getString(R.string.sessions_refresh)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.sessions_refresh)).performClick()
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
    fun overflowMenuShowsExactlyTheThreeEntriesInOrder() {
        setScreen(SessionPageSource { _, _, _ -> success(emptyList(), false) })

        openOverflowMenu()

        composeRule.onNodeWithTag("sessions_overflow_menu")
            .onChildren()
            .assertCountEquals(3)
        val tags = listOf(
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

    private fun setScreen(
        source: SessionPageSource,
        onOpenProfileManagement: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenAbout: () -> Unit = {}
    ) {
        composeRule.setContent {
            HermesTheme {
                SessionsScreen(
                    pageSource = source,
                    profileId = PROFILE_ID,
                    onOpenProfileManagement = onOpenProfileManagement,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout
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
        pinned: Boolean = false
    ) = HermesSession(
        id = id,
        title = title,
        source = source,
        lastActiveEpochSeconds = null,
        messageCount = messageCount,
        pinned = pinned
    )

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
