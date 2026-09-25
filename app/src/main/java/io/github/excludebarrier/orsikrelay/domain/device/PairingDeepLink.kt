package io.github.excludebarrier.orsikrelay.domain.device

import java.net.URI
import java.net.URLDecoder

/**
 * A pairing bootstrap parsed from an `orsik://` deep link: the server base
 * URL + the pairing code.
 *
 * This is the single input a fresh app needs to auto-discover the server and
 * pair — no manual base-URL or API-key entry (Product Gate). The device key
 * (`hd_`) is NEVER carried here; it is obtained only during pairing. The
 * pairing code itself is single-use and short-lived.
 *
 * Pure and Android-independent so [PairingDeepLink.parse] is unit-testable
 * without the framework.
 */
data class PairingBootstrap(
    val baseUrl: String,
    val pairingCode: String,
) {
    /**
     * A sensible connection name derived from the server host, so the user
     * never has to type one. Fails closed to a stable default when the URL
     * has no usable host label.
     */
    val suggestedProfileName: String
        get() = PairingDeepLink.suggestedName(baseUrl)
}

/**
 * Parses `orsik://` pairing deep links.
 *
 * Accepted form: `orsik://pair?server=<urlencoded-base-url>&code=<code>`.
 * The `server` value is percent-decoded (robust to already-plain values too);
 * the `code` is used verbatim and validated later by [DevicePairCode.normalize].
 */
object PairingDeepLink {

    const val SCHEME = "orsik"
    const val HOST = "pair"

    private const val DEFAULT_PROFILE_NAME = "Hermes"

    /**
     * Parse an `orsik://` pairing deep-link string into a [PairingBootstrap].
     *
     * Fail-closed: returns null (never throws) for a blank string, a URL
     * fragment, a wrong scheme/host, a missing query, a missing/empty
     * `server` or `code`, a server URL that is not absolute HTTPS (or that
     * carries user info), or a code that fails [DevicePairCode] contract
     * validation. Malformed percent-encoding also yields null rather than
     * an exception.
     */
    fun parse(uri: String): PairingBootstrap? {
        if (uri.isBlank()) return null
        // A '#' begins a URL fragment, which is not part of the pairing payload.
        if (uri.contains("#")) return null
        try {
            val queryStart = uri.indexOf('?')
            val authority = if (queryStart >= 0) uri.substring(0, queryStart) else uri
            val (scheme, host) = splitAuthority(authority) ?: return null
            if (scheme != SCHEME || host != HOST) return null
            if (queryStart < 0) return null
            val params = parseQuery(uri.substring(queryStart + 1)) ?: return null
            val server = params["server"] ?: return null
            val code = params["code"] ?: return null
            if (server.isEmpty() || code.isEmpty()) return null
            if (!isSecureBaseUrl(server)) return null
            if (DevicePairCode.normalize(code) == null) return null
            return PairingBootstrap(baseUrl = server, pairingCode = code)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * True when [server] is an absolute `https` URL with a host and no user
     * info. Plaintext `http`, schemeless/relative values, and
     * `user:pass@host` are all rejected (no HTTP fallback, no embedded
     * credentials).
     */
    private fun isSecureBaseUrl(server: String): Boolean {
        val uri = try {
            URI(server)
        } catch (_: Exception) {
            return false
        }
        return "https".equals(uri.scheme, ignoreCase = true)
            && !uri.host.isNullOrBlank()
            && uri.userInfo == null
    }

    /**
     * Derive a short, safe profile name from a base URL: the first host label
     * (e.g. `my-host` from `https://my-host.example.net`), lowercased and
     * reduced to letters/digits/hyphen so it always passes name validation.
     * Fails closed to [DEFAULT_PROFILE_NAME].
     */
    fun suggestedName(baseUrl: String): String {
        val host = try {
            URI(baseUrl).host
        } catch (_: Exception) {
            null
        }
        if (host.isNullOrBlank()) return DEFAULT_PROFILE_NAME
        val label = host.substringBefore('.')
        val cleaned = label.filter { it.isLetterOrDigit() || it == '-' }.lowercase()
        if (cleaned.isEmpty()) return DEFAULT_PROFILE_NAME
        return cleaned
    }

    /** Split `scheme://host` or `scheme:host` into a (lowercased scheme, lowercased host) pair. */
    private fun splitAuthority(authority: String): Pair<String, String>? {
        val sep = authority.indexOf("://")
        return if (sep >= 0) {
            val scheme = authority.substring(0, sep).lowercase()
            val host = authority.substring(sep + 3).lowercase()
            if (scheme.isEmpty() || host.isEmpty()) null else scheme to host
        } else {
            val colon = authority.indexOf(':')
            if (colon <= 0) return null
            val scheme = authority.substring(0, colon).lowercase()
            val host = authority.substring(colon + 1).lowercase()
            if (scheme.isEmpty() || host.isEmpty()) null else scheme to host
        }
    }

    /** Parse a `k=v&k=v` query into an ordered map, percent-decoding both sides. */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            map[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
        }
        return map
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
