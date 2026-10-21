package io.github.excludebarrier.orsikrelay.navigation

/**
 * One delivered notification tap.
 *
 * [delivery] is a monotonically increasing sequence number assigned by
 * [io.github.excludebarrier.orsikrelay.MainActivity] for every parsed
 * notification intent. It makes two taps with identical routing IDs distinct
 * state values, so Compose re-runs the navigation effect instead of treating
 * the second tap as the already-consumed first one.
 */
data class NotificationTap(
    val profileId: String,
    val sessionId: String,
    val delivery: Long
)
