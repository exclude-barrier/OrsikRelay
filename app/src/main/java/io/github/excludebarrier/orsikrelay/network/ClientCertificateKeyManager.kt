package io.github.excludebarrier.orsikrelay.network

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/** Access to the system key store's user identities (Android KeyChain in production). */
interface ClientIdentitySource {
    fun privateKey(alias: String): PrivateKey?
    fun certificateChain(alias: String): Array<X509Certificate>?
}

/**
 * Presents a client certificate (mTLS) only to hosts registered for it.
 *
 * Each public endpoint of a server profile registers its host with the
 * KeyChain alias the user picked. Every other host (the private Tailscale
 * endpoint, anything else) gets no certificate, exactly as before. The
 * private key never leaves the system key store; it is used through
 * [ClientIdentitySource] during the handshake only.
 */
class ClientCertificateKeyManager(
    private val source: () -> ClientIdentitySource?
) : X509ExtendedKeyManager() {

    private val aliasByHost = ConcurrentHashMap<String, String>()

    /** Present [alias] to [host] from now on (null removes the binding). */
    fun bind(host: String, alias: String?) {
        val key = host.lowercase()
        if (alias == null) aliasByHost.remove(key) else aliasByHost[key] = alias
    }

    fun aliasFor(host: String?): String? = host?.let { aliasByHost[it.lowercase()] }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        aliasFor(peerHost(socket))

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        aliasFor(engine?.peerHost)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        alias?.let { runCatching { source()?.certificateChain(it) }.getOrNull() }

    override fun getPrivateKey(alias: String?): PrivateKey? =
        alias?.let { runCatching { source()?.privateKey(it) }.getOrNull() }

    // A client never acts as a TLS server.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    /** The handshake's peer host, else the host the socket was opened for. */
    private fun peerHost(socket: Socket?): String? {
        val fromSession = runCatching { (socket as? SSLSocket)?.handshakeSession?.peerHost }.getOrNull()
        return fromSession ?: runCatching { socket?.inetAddress?.hostName }.getOrNull()
    }
}
