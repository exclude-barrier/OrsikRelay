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
        return Result.success(normalize(uri))
    }

    private fun normalize(uri: URI): String {
        val authority = uri.authority ?: return ""
        val path = uri.path.orEmpty().trimEnd('/')
        return if (path.isEmpty()) {
            "https://$authority"
        } else {
            "https://$authority$path"
        }
    }
}
