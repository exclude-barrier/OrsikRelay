package io.github.excludebarrier.orsikrelay.testdiagnostics

/** Only for synthetic fixture screens: never dump live sessions or credentials. */
internal fun withUiFailureDiagnostics(describe: () -> String, block: () -> Unit) {
    try {
        block()
    } catch (failure: Throwable) {
        // Keep the original failure/type/stack. JUnit includes suppressed errors
        // in its XML and logcat; collecting evidence must never turn a test green.
        val details = try {
            describe().take(16_000)
        } catch (diagnosticFailure: Throwable) {
            "collection failed: ${diagnosticFailure.javaClass.simpleName}"
        }
        failure.addSuppressed(AssertionError("UI failure diagnostics (synthetic fixtures only):\n$details"))
        throw failure
    }
}
