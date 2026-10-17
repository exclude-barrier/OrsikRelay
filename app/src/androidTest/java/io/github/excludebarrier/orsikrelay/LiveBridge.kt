package io.github.excludebarrier.orsikrelay

/**
 * Marks live end-to-end instrumented tests that require a running Orsik Mobile
 * Bridge (and the official Hermes core behind it) reachable over HTTPS, plus
 * `liveBaseUrl` / `livePairingCode` instrumentation arguments.
 *
 * They are excluded from the regular connected suite (like [CaptureOnly]) via
 * [ManualTestsFilter]
 * (`-Pandroid.testInstrumentationRunnerArguments.filter=io.github.excludebarrier.orsikrelay.ManualTestsFilter`)
 * and run explicitly against the live stack. Without the arguments the JUnit
 * assumption marks them skipped, so an accidental inclusion never fails CI.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class LiveBridge
