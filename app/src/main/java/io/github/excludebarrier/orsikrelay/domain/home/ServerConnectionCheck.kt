package io.github.excludebarrier.orsikrelay.domain.home

import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult

/**
 * Reachability of a saved server, as the Home header shows it: the same
 * health + capabilities probe the onboarding uses, for a stored profile.
 * Blocking; call it off the main thread. Never retries.
 */
fun interface ServerConnectionCheck {
    fun check(profileId: String): HermesConnectionResult
}
