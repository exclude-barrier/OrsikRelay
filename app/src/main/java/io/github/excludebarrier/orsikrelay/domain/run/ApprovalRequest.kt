package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed UI model for one protocol-v1 mobile session-chat approval.
 *
 * Only the routing identifiers needed to answer the exact pending request and
 * the protocol-defined neutral description cross this boundary. The identifier
 * formats are the exact server-generated forms (`run_` + UUID hex and UUID
 * hex). Commands, arguments, paths, URLs, tool output, pattern keys, API keys
 * and arbitrary dynamic text are therefore impossible to represent.
 */
data class ApprovalRequest(
    /** Exact server-generated run ID; used only to build the response URL. */
    val runId: String,
    /** Exact server-generated pending request ID; sent only in the body. */
    val requestId: String,
    /** Fixed protocol-defined text, never tool- or command-derived. */
    val description: String
) {
    init {
        require(isSafeRunId(runId))
        require(isSafeRequestId(requestId))
        require(description == SAFE_DESCRIPTION)
    }

    companion object {
        const val SAFE_DESCRIPTION = "A dangerous action requires your approval."
        private val RUN_ID = Regex("run_[0-9a-f]{32}")
        private val REQUEST_ID = Regex("[0-9a-f]{32}")

        internal fun isSafeRunId(value: String): Boolean = RUN_ID.matches(value)
        internal fun isSafeRequestId(value: String): Boolean = REQUEST_ID.matches(value)
    }
}
