package io.github.excludebarrier.orsikrelay.domain.profile

/** Which of a server's two endpoints the app may use. */
enum class ConnectionMode {
    /** Only the paired (private, e.g. Tailscale) endpoint. The default. */
    PRIVATE_ONLY,

    /** The private endpoint when it answers, else the verified public one. */
    AUTOMATIC,

    /** Only the verified public endpoint (own domain with mTLS). */
    PUBLIC_ONLY
}

/**
 * The second, public endpoint of a server profile.
 *
 * The device key only ever goes to the paired base URL or to [publicBaseUrl]
 * after the bridge there reported the same [serverId] that was pinned
 * from the paired endpoint. [clientCertAlias] names the Android KeyChain
 * identity presented to the public reverse proxy (mTLS); the private key
 * itself never leaves the system key store.
 */
@ConsistentCopyVisibility
data class ConnectionSettings private constructor(
    val mode: ConnectionMode,
    val publicBaseUrl: String?,
    val clientCertAlias: String?,
    val serverId: String?
) {
    val usesPublicEndpoint: Boolean get() = mode != ConnectionMode.PRIVATE_ONLY

    /** Same settings with the server identity pinned (or replaced). */
    fun withServerId(id: String): ConnectionSettings =
        create(mode, publicBaseUrl, clientCertAlias, id)

    companion object {
        private const val INVALID_MESSAGE = "Invalid connection settings"
        private const val MAX_ALIAS_LENGTH = 256
        private val SERVER_ID = Regex("[0-9a-f]{32}")

        val PRIVATE_ONLY: ConnectionSettings =
            ConnectionSettings(ConnectionMode.PRIVATE_ONLY, null, null, null)

        fun isValidServerId(value: String): Boolean = SERVER_ID.matches(value)

        fun create(
            mode: ConnectionMode,
            publicBaseUrl: String?,
            clientCertAlias: String?,
            serverId: String?
        ): ConnectionSettings {
            if (publicBaseUrl != null &&
                ProfileBaseUrlValidator.validate(publicBaseUrl).getOrNull() != publicBaseUrl
            ) {
                throw IllegalArgumentException(INVALID_MESSAGE)
            }
            if (clientCertAlias != null &&
                (clientCertAlias.isBlank() || clientCertAlias.length > MAX_ALIAS_LENGTH ||
                    clientCertAlias.any { it.isISOControl() })
            ) {
                throw IllegalArgumentException(INVALID_MESSAGE)
            }
            if (serverId != null && !isValidServerId(serverId)) {
                throw IllegalArgumentException(INVALID_MESSAGE)
            }
            if (mode != ConnectionMode.PRIVATE_ONLY && publicBaseUrl == null) {
                throw IllegalArgumentException(INVALID_MESSAGE)
            }
            return ConnectionSettings(mode, publicBaseUrl, clientCertAlias, serverId)
        }
    }
}
