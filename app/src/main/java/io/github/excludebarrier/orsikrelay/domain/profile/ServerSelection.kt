package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * Deterministic outcome of validating the persisted active server selection.
 *
 * [Valid] holds the profile ID only when the stored ID still exists in the
 * server metadata AND has a credential. [Invalid] covers every other case
 * (absent, blank, deleted or inconsistent selection, or a failed read): the
 * caller falls back to the existing safe startup resolution, and no ID or
 * error detail escapes this type.
 */
sealed interface ServerSelection {
    data class Valid(val profileId: String) : ServerSelection
    data object Invalid : ServerSelection
}
