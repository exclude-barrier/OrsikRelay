package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.crash.CrashReport
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter
import io.github.excludebarrier.orsikrelay.ui.crash.CrashReportDialog
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun concurrentRecordWritesStayCompleteAndLeaveNoTempFile() {
        CrashReporter.clear(context)
        val environment = CrashReporter.environment()
        // Each writer uses a distinct, large report (the stack trace is
        // carried verbatim), so a non-atomic truncate+write would leave a
        // mixed or truncated file behind.
        val writers = 8
        val reports = (0 until writers).map { index ->
            CrashReport.format(throwableFor(index), environment)
        }.toSet()
        val threads = (0 until writers).map { index ->
            Thread {
                repeat(50) {
                    CrashReporter.record(context, throwableFor(index))
                }
            }
        }

        threads.forEach(Thread::start)
        threads.forEach { it.join(10_000) }
        assertTrue("all writers must finish", threads.none { it.isAlive })

        val report = checkNotNull(CrashReporter.pending(context))
        assertTrue("the stored report must be one complete write", report in reports)
        assertTrue(
            "no temporary report file may survive",
            context.noBackupFilesDir.listFiles().orEmpty().none {
                it.name.startsWith("last-crash") && it.name != "last-crash.txt"
            }
        )
        CrashReporter.clear(context)
    }

    @Test
    fun installIsIdempotent() {
        // The app's Application has already installed the crash handler in
        // this process. A second install must not stack another recorder in
        // front of it.
        val before = Thread.getDefaultUncaughtExceptionHandler()
        CrashReporter.install(context)

        assertNotNull(before)
        assertSame(before, Thread.getDefaultUncaughtExceptionHandler())
    }

    private fun throwableFor(index: Int) = IllegalStateException().apply {
        stackTrace = arrayOf(
            StackTraceElement("fixture.Writer$index", "run", "Writer.kt", index + 1),
            StackTraceElement("fixture.Writer$index", "loop", "Writer.kt", index + 101)
        )
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
