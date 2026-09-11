package io.github.excludebarrier.orsikrelay.data.profile

/**
 * Closed, fixed failure surface for the run-approval capability store
 * (Mission 014).
 *
 * Mirrors [ActiveServerSelectionStoreException]: every store failure maps
 * to this one generic message. It never carries a profile ID, a URL or any
 * dynamic detail, so no stored value can leak through an error.
 */
class RunApprovalCapabilityStoreException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Run-approval capability persistence operation failed"
    }
}
