package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.ui.sessions.NewSessionDialog
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose tests for the new-session dialog (Mission 005A).
 *
 * All values are synthetic fixtures; the creator seam is a fake, so no
 * network, no real hosts and no credentials are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionDialogUiTest {

    // The dialog's create call is a BLOCKING seam executed on `Dispatchers.IO`
    // (see `NewSessionDialog.submit`). The default `createComposeRule()` uses an
    // UnconfinedTestDispatcher for the composition, so the post-I/O continuation
    // (`submitting = false`, `onCreated`) resumes on the IO worker and the
    // resulting recomposition would dispose the dialog's own composition off the
    // main thread (`IllegalStateException: removeObserver must be called on the
    // main thread`). A confined StandardTestDispatcher queues that continuation
    // on the test scheduler, so every state write and disposal runs on the test
    // (main) thread. This keeps the blocking fake and exercises the real
    // completion path.
    @get:Rule
    val composeRule = createComposeRule(StandardTestDispatcher())

    @Test
    fun cancelClosesTheDialogAndSendsNoRequest() {
        val calls = AtomicInteger()
        setDialog(creator { _, _ ->
            calls.incrementAndGet()
            SessionCreateResult.Success(CREATED_ID, null)
        })

        composeRule.onNodeWithText("Neue Session").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").performClick()

        waitForDialogGone()
        assertEquals(0, calls.get())
    }

    @Test
    fun successfulCreateWithoutTitlePassesNullAndClosesViaCallback() {
        val titles = mutableListOf<String?>()
        var createdId: String? = null
        setDialog(
            creator { profileId, title ->
                assertEquals(PROFILE_ID, profileId)
                titles += title
                SessionCreateResult.Success(CREATED_ID, null)
            },
            onCreated = { createdId = it }
        )

        composeRule.onNodeWithText("Erstellen").performClick()
        waitForDialogGone()

        assertEquals(listOf<String?>(null), titles)
        assertEquals(CREATED_ID, createdId)
    }

    @Test
    fun successfulCreateWithOptionalTitlePassesTheRawText() {
        val titles = mutableListOf<String?>()
        setDialog(
            creator { _, title ->
                titles += title
                SessionCreateResult.Success(CREATED_ID, title)
            }
        )

        composeRule
            .onNodeWithText("Titel (optional)")
            .performTextInput("  Synthetischer Titel  ")
        composeRule.onNodeWithText("Erstellen").performClick()
        waitForDialogGone()

        assertEquals(listOf("  Synthetischer Titel  "), titles)
    }

    @Test
    fun failureShowsGenericMessageAndRetryIsManual() {
        val calls = AtomicInteger()
        setDialog(
            creator { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    SessionCreateResult.Failure(SessionCreateError.Network)
                } else {
                    SessionCreateResult.Success(CREATED_ID, null)
                }
            }
        )

        composeRule.onNodeWithText("Erstellen").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Die neue Session konnte nicht angelegt werden.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(1, calls.get())

        composeRule.onNodeWithText("Erneut versuchen").performClick()
        waitForDialogGone()
        assertEquals(2, calls.get())
    }

    @Test
    fun doubleTapOnCreateStartsExactlyOneRequest() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        setDialog(
            creator { _, _ ->
                calls.incrementAndGet()
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                SessionCreateResult.Success(CREATED_ID, null)
            }
        )

        composeRule.onNodeWithText("Erstellen").performClick()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        composeRule.onNodeWithText("Erstellen").performClick()
        assertEquals(1, calls.get())

        release.countDown()
        waitForDialogGone()
        assertEquals(1, calls.get())
    }

    private fun setDialog(
        creator: SessionCreator,
        onCreated: (sessionId: String) -> Unit = {}
    ) {
        composeRule.setContent {
            var showDialog by remember { mutableStateOf(true) }
            HermesTheme {
                if (showDialog) {
                    NewSessionDialog(
                        creator = creator,
                        profileId = PROFILE_ID,
                        onDismiss = { showDialog = false },
                        onCreated = { sessionId ->
                            showDialog = false
                            onCreated(sessionId)
                        }
                    )
                }
            }
        }
    }

    private fun waitForDialogGone() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Neue Session")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun creator(block: (String, String?) -> SessionCreateResult) =
        SessionCreator(block)

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val CREATED_ID = "synthetic-created-id"
    }
}
