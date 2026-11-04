package io.github.excludebarrier.orsikrelay.security.clientcert

import android.content.Context
import android.security.KeyChain
import io.github.excludebarrier.orsikrelay.network.ClientIdentitySource
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * The user's certificates in the Android system key store. The app only
 * gets an alias the user granted it in the system picker
 * ([KeyChain.choosePrivateKeyAlias]); the private key stays in the key store
 * and is used through a handle during the TLS handshake. Blocking: KeyChain
 * must not be called on the main thread (the handshake never is).
 */
class KeyChainIdentitySource(context: Context) : ClientIdentitySource {
    private val context = context.applicationContext

    override fun privateKey(alias: String): PrivateKey? = KeyChain.getPrivateKey(context, alias)

    override fun certificateChain(alias: String): Array<X509Certificate>? =
        KeyChain.getCertificateChain(context, alias)
}
