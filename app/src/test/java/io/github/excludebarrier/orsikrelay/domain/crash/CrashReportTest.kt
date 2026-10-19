package io.github.excludebarrier.orsikrelay.domain.crash

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    private val environment = CrashReport.environment("0.1.0 (10)", "16 (API 36)", "Google Pixel 9")

    @Test
    fun reportKeepsTypesAndFramesButNeverMessages() {
        val cause = IllegalStateException("content://media/secret/42")
        val crash = RuntimeException("https://hermes.example/v1?key=secret", cause)

        val report = CrashReport.format(crash, environment)

        assertTrue(report.startsWith("App: 0.1.0 (10)\nAndroid: 16 (API 36)\nDevice: Google Pixel 9\n"))
        assertTrue(report.contains("\njava.lang.RuntimeException\n"))
        assertTrue(report.contains("\nCaused by: java.lang.IllegalStateException\n"))
        assertTrue(report.contains("    at io.github.excludebarrier.orsikrelay.domain.crash.CrashReportTest"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("hermes.example"))
    }

    @Test
    fun framesAndCausesAreBounded() {
        val deep = RuntimeException()
        deep.stackTrace = Array(CrashReport.MAX_FRAMES + 7) {
            StackTraceElement("a.B", "m$it", "B.kt", it)
        }
        var chain: Throwable = deep
        repeat(CrashReport.MAX_CAUSES + 3) { chain = RuntimeException(chain) }

        val report = CrashReport.format(deep, environment)
        assertEquals(CrashReport.MAX_FRAMES, report.lines().count { it.startsWith("    at a.B.m") })
        assertTrue(report.contains("    ... 7 more"))

        val causes = CrashReport.format(chain, environment).lines().count { it.startsWith("Caused by: ") }
        assertEquals(CrashReport.MAX_CAUSES - 1, causes)
    }

    @Test
    fun selfReferencingCauseDoesNotLoop() {
        val a = RuntimeException()
        val b = IllegalStateException(a)
        a.initCause(b)
        val report = CrashReport.format(a, environment)
        assertEquals(1, report.lines().count { it.startsWith("Caused by: ") })
    }

    @Test
    fun crashTitleIsTheOutermostExceptionType() {
        val report = CrashReport.format(IllegalArgumentException("x"), environment)
        assertEquals("Crash: IllegalArgumentException", CrashReport.crashTitle(report))
        assertEquals("Crash", CrashReport.crashTitle(environment))
    }

    @Test
    fun issueUrlIsEncodedAndBounded() {
        val url = CrashReport.issueUrl(
            projectUrl = "https://github.com/exclude-barrier/OrsikRelay",
            title = "Crash: X",
            report = "a b&c=d\n" + "x".repeat(CrashReport.MAX_ISSUE_BODY_CHARS)
        )
        assertTrue(url.startsWith("https://github.com/exclude-barrier/OrsikRelay/issues/new?title=Crash%3A%20X&body="))
        assertFalse(url.contains(' '))
        assertFalse(url.contains('+'))
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("a b&c=d\n"))
        assertTrue(body.endsWith("x\n...\n```\n"))
        assertTrue(body.length < CrashReport.MAX_ISSUE_BODY_CHARS + 100)
    }
}
