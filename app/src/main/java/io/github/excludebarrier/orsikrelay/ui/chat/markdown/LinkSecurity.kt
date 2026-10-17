package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.UriHandler

/**
 * Fail-closed scheme allowlist that gates markdown link navigation.
 *
 * Only absolute HTTPS URLs are opened. Every other scheme is rejected:
 * `javascript:`, `file://`, `intent://`, `data:`, unknown custom schemes,
 * and scheme-less relative targets. This is the single security boundary for
 * markdown links; it is a pure function so it is unit-testable without
 * Android or Compose. It never logs or surfaces the rejected target.
 */
internal object LinkSchemePolicy {

    private val ALLOWED_SCHEMES = setOf("https")

    fun isAllowed(raw: String): Boolean {
        val trimmed = raw.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return false
        val scheme = trimmed.substring(0, colon)
        if (scheme.firstOrNull()?.isLetter() != true) return false
        if (scheme.any { !(it.isLetterOrDigit() || it == '+' || it == '.' || it == '-') }) return false
        return scheme.lowercase() in ALLOWED_SCHEMES
    }
}

/**
 * A [UriHandler] that opens only URLs accepted by [LinkSchemePolicy]. A
 * rejected URL is a silent no-op (no navigation, no crash, no log of the
 * target). [onAllowedLinkOpen] is a test seam: when non-null it receives the
 * allowed URL string instead of a real intent launch.
 */
@Composable
internal fun rememberSecureUriHandler(
    context: Context,
    onAllowedLinkOpen: ((String) -> Unit)? = null,
): UriHandler = remember(context, onAllowedLinkOpen) {
    object : UriHandler {
        override fun openUri(uri: String) {
            if (LinkSchemePolicy.isAllowed(uri)) {
                if (onAllowedLinkOpen != null) {
                    onAllowedLinkOpen(uri)
                } else {
                    launchView(context, uri)
                }
            }
        }
    }
}

private fun launchView(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
