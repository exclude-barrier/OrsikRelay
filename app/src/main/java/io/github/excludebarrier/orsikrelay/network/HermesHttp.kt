package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Shared, fail-closed HTTP plumbing for the one-shot network clients.
 *
 * Status mapping, 409 special cases and JSON parsing stay in each client;
 * only the byte-identical parts live here.
 */

/** Maximum accepted length of a bearer API key. */
internal const val MAX_API_KEY_CHARACTERS = 4096

private const val MAX_OPAQUE_ID_LENGTH = 256
private const val BUFFER_SIZE = 8 * 1024
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val JSON_MEDIA_TYPE = "application/json"

internal object HermesHttp {
    /** The user identities in the system key store (set once by the app). */
    @Volatile
    var identitySource: ClientIdentitySource? = null

    /**
     * Client certificates (mTLS) per host: only a public endpoint bound by
     * [ClientCertificateKeyManager.bind] gets one. Server trust stays the
     * platform default (system CAs and the network security config).
     */
    val clientCertificates = ClientCertificateKeyManager { identitySource }

    /**
     * Process-wide base client. Every production default derives from it via
     * [OkHttpClient.newBuilder], so all clients share one dispatcher and one
     * connection pool. Redirects and connection retries stay disabled.
     */
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply { withClientCertificates(this) }
        .build()

    private fun withClientCertificates(builder: OkHttpClient.Builder) {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val trustManager = factory.trustManagers.single() as X509TrustManager
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(clientCertificates), arrayOf(trustManager), null)
        builder.sslSocketFactory(context.socketFactory, trustManager)
    }

    /**
     * One-shot default: connect/write 10 s, read 10 s and a 15 s call
     * deadline, unless a caller (the streaming client) needs other bounds.
     */
    fun defaultClient(
        readTimeoutSeconds: Long = 10,
        callTimeoutSeconds: Long = 15
    ): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}

/** Non-empty, at most [MAX_API_KEY_CHARACTERS], no whitespace or control characters. */
internal fun isSafeApiKey(apiKey: SecretValue): Boolean = apiKey.useRaw { rawKey ->
    rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
        rawKey.none { character ->
            character.isWhitespace() || character.isISOControl()
        }
}

/** Non-blank, at most [maxLength] and free of ISO control characters. */
internal fun isSafeOpaqueId(value: String, maxLength: Int = MAX_OPAQUE_ID_LENGTH): Boolean {
    if (value.isBlank()) return false
    if (value.length > maxLength) return false
    return value.none { it.isISOControl() }
}

/**
 * Only a JSON media type is accepted (parameters such as a charset are
 * allowed); anything else fails through [onInvalid].
 */
internal inline fun requireJsonContentType(response: Response, onInvalid: () -> Nothing) {
    val type = response.header(CONTENT_TYPE_HEADER)
        ?.substringBefore(';')
        ?.trim()
    if (!JSON_MEDIA_TYPE.equals(type, ignoreCase = true)) {
        onInvalid()
    }
}

/**
 * Reads at most [maxBytes] body bytes and decodes them as strict UTF-8.
 * A missing body, an oversized body or malformed/unmappable input fails
 * through [onInvalid], so each client maps the violation to its own error
 * type.
 */
internal inline fun readBoundedUtf8(
    body: ResponseBody?,
    maxBytes: Int,
    onInvalid: () -> Nothing
): String {
    val responseBody = body ?: onInvalid()
    if (responseBody.contentLength() > maxBytes) {
        onInvalid()
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    responseBody.byteStream().use { input ->
        var total = 0
        while (true) {
            val remaining = maxBytes + 1 - total
            if (remaining <= 0) {
                onInvalid()
            }
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
            if (total > maxBytes) {
                onInvalid()
            }
        }
    }
    val bytes = output.toByteArray()
    val text = try {
        decodeUtf8Strict(bytes)
    } finally {
        bytes.fill(0)
        buffer.fill(0)
    }
    return text ?: onInvalid()
}
