package io.github.excludebarrier.orsikrelay

import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * Excludes the manual instrumentation tests – [CaptureOnly] (screenshot and
 * real-keyboard captures) and [LiveBridge] (needs a running bridge) – from a
 * regular connected run.
 *
 * Pass it as a single runner argument:
 * `-Pandroid.testInstrumentationRunnerArguments.filter=io.github.excludebarrier.orsikrelay.ManualTestsFilter`.
 * It replaces `notAnnotation=…CaptureOnly,…LiveBridge`: since AGP 9.4.1 only
 * the first value of a comma-separated runner argument reaches the runner, so
 * that form silently stopped excluding [LiveBridge].
 */
class ManualTestsFilter : Filter() {

    override fun shouldRun(description: Description): Boolean {
        if (description.isTest) return !isManual(description)
        // A suite runs if at least one of its tests does.
        return description.children.any { shouldRun(it) }
    }

    override fun describe(): String = "excludes @CaptureOnly and @LiveBridge tests"

    private fun isManual(description: Description): Boolean =
        description.getAnnotation(CaptureOnly::class.java) != null ||
            description.getAnnotation(LiveBridge::class.java) != null ||
            description.testClass?.isAnnotationPresent(LiveBridge::class.java) == true
}
