package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter
import io.github.excludebarrier.orsikrelay.ui.crash.CrashReportDialog
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The stored crash report and the dialog that offers it after a restart. */
class CrashReportUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordedCrashIsPendingWithoutItsMessageUntilCleared() {
        CrashReporter.clear(context)
        assertNull(CrashReporter.pending(context))

        CrashReporter.record(context, IllegalStateException("content://secret"))

        val report = checkNotNull(CrashReporter.pending(context))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertFalse(report.contains("secret"))
        CrashReporter.clear(context)
        assertNull(CrashReporter.pending(context))
    }

    @Test
    fun dialogOffersReportShareAndDiscard() {
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            HermesTheme {
                CrashReportDialog(
                    onReport = { clicks += "report" },
                    onShare = { clicks += "share" },
                    onDiscard = { clicks += "discard" },
                    onPostpone = { clicks += "postpone" }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.crash_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.crash_dialog_report)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.crash_dialog_share)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.crash_dialog_discard)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("report", "share", "discard"), clicks)
    }
}
