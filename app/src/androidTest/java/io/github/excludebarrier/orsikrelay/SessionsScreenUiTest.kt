package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("7 Nachrichten").assertIsDisplayed()
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

    private fun setScreen(source: SessionPageSource) {
        composeRule.setContent {
            HermesTheme {
                SessionsScreen(source, PROFILE_ID)
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
