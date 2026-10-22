package io.github.excludebarrier.orsikrelay.domain.profile

import java.net.URI
import java.net.URISyntaxException

/**
 * Pure, local validation and normalization of the server base URL.
 * No network access, no I/O.
 *
 * Rules:
 * - absolute `https` URL (scheme is case-insensitive);
 * - a host is required;
 * - no user info (`user:pass@host` is rejected);
 * - no query and no fragment;
 * - a path is allowed because Hermes supports profile prefixes such as
 *   `/p/<profile>`;
 * - normalized output without a trailing slash, except for the origin
 *   root.
 *
 * Failure messages are stable, generic reasons. They never echo user
 * input, so no credentials can leak through them.
 */
object ProfileBaseUrlValidator {

    private const val HTTPS = "https"

    /**
     * Validates and normalizes [rawUrl].
     *
     * @return success with the normalized URL or a failure carrying a
     * stable reason (never the user input).
     */
    fun validate(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("base url must not be empty")
            )
        }
        val uri: URI = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return Result.failure(
                IllegalArgumentException("base url is not a parseable URI")
            )
        }
        if (!HTTPS.equals(uri.scheme, ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("base url must be an absolute https url")
            )
        }
        if (uri.host.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("base url requires a host")
            )
        }
        if (uri.userInfo != null) {
            return Result.failure(
                IllegalArgumentException("base url must not contain user info")
            )
        }
        if (uri.query != null || uri.fragment != null) {
            return Result.failure(
                IllegalArgumentException("base url must not contain a query or fragment")
            )
        }
        // A usable TCP port only: no 0, nothing above 65535, no empty ":".
        if ((uri.port != -1 && uri.port !in 1..65535) || uri.rawAuthority.orEmpty().endsWith(":")) {
            return Result.failure(
                IllegalArgumentException("base url has an invalid port")
            )
        }
        return Result.success(normalize(uri))
    }

    /**
     * Rebuilds the URL from the RAW authority and path, so percent-encoding
     * survives (a %20 or %2F is never decoded into a space or a new segment)
     * and the result validates again unchanged.
     */
    private fun normalize(uri: URI): String {
        val authority = uri.rawAuthority ?: return ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return if (path.isEmpty()) {
            "https://$authority"
        } else {
            "https://$authority$path"
        }
    }
}
