package io.github.excludebarrier.orsikrelay.data.profile

/**
 * Closed, fixed failure surface for the active server selection store.
 *
 * Mirrors [ProfileMetadataStoreException]: every store failure maps to this
 * one generic message. It never carries a profile ID, a URL or any dynamic
 * detail, so no stored value can leak through an error.
 */
class ActiveServerSelectionStoreException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Active server selection persistence operation failed"
    }
}
