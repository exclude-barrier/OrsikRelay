package io.github.excludebarrier.orsikrelay.domain.profile

/** Which base URL a call for a profile goes to right now. */
fun interface ConnectionEndpoints {
    /**
     * The paired base URL, or the public one when the profile allows it and
     * its server identity was verified. Blocking (may probe the network);
     * call it off the main thread. Never returns an unverified endpoint.
     */
    fun baseUrlFor(profile: ServerProfile): String

    companion object {
        /** Always the paired endpoint (tests, and before anything is wired). */
        val PAIRED_ONLY = ConnectionEndpoints { it.baseUrl }
    }
}
