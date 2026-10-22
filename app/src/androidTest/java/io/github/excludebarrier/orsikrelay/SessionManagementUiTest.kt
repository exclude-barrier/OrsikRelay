package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManager
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSION_ARCHIVE_TAG
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSION_DELETE_CONFIRM_TAG
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSION_DELETE_DIALOG_TAG
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSION_DELETE_TAG
import io.github.excludebarrier.orsikrelay.ui.sessions.SessionsScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * alpha.12 chat management in the Agents list: long-press offers archive
 * (with undo) and delete (with confirmation) only when the server advertises
 * them; a row disappears only after the server confirmed the action.
 */
class SessionManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeManager(
        private val supported: Boolean = true,
        private val archiveResult: SessionManageResult = SessionManageResult.Success,
        private val deleteResult: SessionManageResult = SessionManageResult.Success
    ) : SessionManager {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun supportsManagement(profileId: String) = supported
        override fun setArchived(profileId: String, sessionId: String, archived: Boolean): SessionManageResult {
            calls += "archive:$sessionId:$archived"
            return archiveResult
        }
        override fun delete(profileId: String, sessionId: String): SessionManageResult {
            calls += "delete:$sessionId"
            return deleteResult
        }
    }

    private val sessions = listOf(
        HermesSession(id = "s1", title = "Logs aufräumen", source = null, lastActiveEpochSeconds = null, messageCount = 4, pinned = false),
        HermesSession(id = "s2", title = "Backup prüfen", source = null, lastActiveEpochSeconds = null, messageCount = 2, pinned = false)
    )

    private fun show(manager: SessionManager?, runningSessionId: String? = null) {
        composeRule.setContent {
            HermesTheme {
                SessionsScreen(
                    pageSource = SessionPageSource { _, _, _ -> SessionListResult.Success(SessionPage(sessions, false)) },
                    profileId = "profile",
                    manager = manager,
                    runningSessionId = runningSessionId
                )
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Logs aufräumen").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    private fun longPress(id: String) {
        composeRule.onNodeWithTag("sessionRow:$id").performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun waitGone(text: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitShown(text: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun withoutAdvertisementThereIsNoLongPressAction() {
        show(FakeManager(supported = false))

        composeRule.onNodeWithTag("sessionRow:s1")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        longPress("s1")
        composeRule.onAllNodesWithTag(SESSION_ARCHIVE_TAG).assertCountEquals(0)
    }

    @Test
    fun archiveHidesTheChatAndUndoBringsItBack() {
        val manager = FakeManager()
        show(manager)

        longPress("s1")
        composeRule.onNodeWithTag(SESSION_ARCHIVE_TAG).performClick()
        waitGone("Logs aufräumen")
        waitShown(context.getString(R.string.sessions_archived))
        composeRule.onNodeWithText(context.getString(R.string.sessions_undo)).performClick()
        waitShown("Logs aufräumen")

        assertEquals(listOf("archive:s1:true", "archive:s1:false"), manager.calls.toList())
        composeRule.onNodeWithText("Backup prüfen").assertIsDisplayed()
    }

    @Test
    fun deleteAsksFirstAndCancelSendsNothing() {
        val manager = FakeManager()
        show(manager)

        longPress("s2")
        composeRule.onNodeWithTag(SESSION_DELETE_TAG).performClick()
        composeRule.onNodeWithTag(SESSION_DELETE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_delete_text, "Backup prüfen")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_delete_cancel)).performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), manager.calls.toList())
        composeRule.onNodeWithText("Backup prüfen").assertIsDisplayed()

        longPress("s2")
        composeRule.onNodeWithTag(SESSION_DELETE_TAG).performClick()
        composeRule.onNodeWithTag(SESSION_DELETE_CONFIRM_TAG).performClick()
        waitGone("Backup prüfen")
        waitShown(context.getString(R.string.sessions_deleted))
        assertEquals(listOf("delete:s2"), manager.calls.toList())
    }

    @Test
    fun aRunningTurnKeepsTheChatAndSaysWhy() {
        show(FakeManager(deleteResult = SessionManageResult.Failure(SessionManageError.ActiveTurn)))

        longPress("s1")
        composeRule.onNodeWithTag(SESSION_DELETE_TAG).performClick()
        composeRule.onNodeWithTag(SESSION_DELETE_CONFIRM_TAG).performClick()

        waitShown(context.getString(R.string.sessions_manage_active))
        composeRule.onNodeWithText("Logs aufräumen").assertIsDisplayed()
    }

    @Test
    fun aFailedArchiveKeepsTheChat() {
        show(FakeManager(archiveResult = SessionManageResult.Failure(SessionManageError.Failed)))

        longPress("s1")
        composeRule.onNodeWithTag(SESSION_ARCHIVE_TAG).performClick()

        waitShown(context.getString(R.string.sessions_manage_failed))
        composeRule.onNodeWithText("Logs aufräumen").assertIsDisplayed()
    }

    @Test
    fun theRunningChatOffersNoActions() {
        show(FakeManager(), runningSessionId = "s1")

        composeRule.onNodeWithTag("sessionRow:s1")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        composeRule.onNodeWithTag("sessionRow:s2")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }
}
