package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.testdiagnostics.withUiFailureDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFailureDiagnosticsTest {
    @Test
    fun successDoesNotCollectDiagnostics() {
        var collected = false
        withUiFailureDiagnostics(describe = { collected = true; "unused" }) { }
        assertFalse(collected)
    }

    @Test
    fun originalFailureIsRethrownWithDiagnosticsInItsStackTrace() {
        val original = AssertionError("original assertion")
        try {
            withUiFailureDiagnostics(describe = { "calls=0; button=disabled" }) { throw original }
            throw AssertionError("failure was swallowed")
        } catch (failure: AssertionError) {
            assertSame(original, failure)
            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.stackTraceToString().contains("calls=0; button=disabled"))
        }
    }

    @Test
    fun failedCollectionDoesNotReplaceTheOriginalFailure() {
        val original = IllegalStateException("original failure")
        try {
            withUiFailureDiagnostics(describe = { error("untrusted collector detail") }) { throw original }
            throw AssertionError("failure was swallowed")
        } catch (failure: IllegalStateException) {
            assertSame(original, failure)
            val diagnostic = failure.suppressed.single().message.orEmpty()
            assertTrue(diagnostic.contains("IllegalStateException"))
            assertFalse(diagnostic.contains("untrusted collector detail"))
        }
    }

    @Test
    fun diagnosticOutputIsBounded() {
        val original = AssertionError("original assertion")
        try {
            withUiFailureDiagnostics(describe = { "x".repeat(100_000) }) { throw original }
            throw AssertionError("failure was swallowed")
        } catch (failure: AssertionError) {
            assertSame(original, failure)
            assertTrue(failure.suppressed.single().message.orEmpty().length <= 16_100)
        }
    }
}
