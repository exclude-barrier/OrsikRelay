package io.github.excludebarrier.orsikrelay.domain.notification

/**
 * Answers whether the app process is currently in the foreground. The
 * production implementation tracks the single [ComponentActivity] start/stop
 * count; tests inject a plain value. This is the only lifecycle signal the
 * notification decision consumes, so the policy stays pure and testable.
 */
interface AppForegroundState {
    val isForeground: Boolean
}
