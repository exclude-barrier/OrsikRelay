package io.github.excludebarrier.orsikrelay

/**
 * Marks visualization/capture-only instrumented tests. They drive a real IME
 * and/or emit host-side screenshot markers, carry **no assertions**, and are
 * therefore excluded from CI via [ManualTestsFilter]
 * (`-Pandroid.testInstrumentationRunnerArguments.filter=io.github.excludebarrier.orsikrelay.ManualTestsFilter`).
 * They remain runnable locally on a headed emulator for manual inspection.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class CaptureOnly
