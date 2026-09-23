package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the fail-closed markdown link scheme allowlist (P2).
 *
 * The policy is the single security boundary for markdown link navigation:
 * only absolute HTTPS URLs may be opened. Everything else — dangerous schemes
 * (`javascript:`, `file://`, `intent://`, `data:`), unknown custom schemes,
 * and scheme-less relative targets — must be rejected so a markdown link can
 * never launch a non-web intent or leak credentials.
 */
class LinkSchemePolicyTest {

    @Test
    fun allowsAbsoluteHttpsUrls() {
        assertTrue(LinkSchemePolicy.isAllowed("https://example.com"))
        assertTrue(LinkSchemePolicy.isAllowed("https://example.com/path?a=1&b=2"))
        assertTrue(LinkSchemePolicy.isAllowed("https://sub.domain.example:8443/x"))
    }

    @Test
    fun httpsMatchIsCaseInsensitiveAndTrimmed() {
        assertTrue(LinkSchemePolicy.isAllowed("HTTPS://EXAMPLE.COM"))
        assertTrue(LinkSchemePolicy.isAllowed("  https://example.com  "))
    }

    @Test
    fun rejectsJavaScriptScheme() {
        assertFalse(LinkSchemePolicy.isAllowed("javascript:alert(document.domain)"))
        assertFalse(LinkSchemePolicy.isAllowed("JavaScript:alert(1)"))
    }

    @Test
    fun rejectsFileScheme() {
        assertFalse(LinkSchemePolicy.isAllowed("file:///etc/passwd"))
        assertFalse(LinkSchemePolicy.isAllowed("file:///sdcard/secret.txt"))
    }

    @Test
    fun rejectsIntentScheme() {
        assertFalse(LinkSchemePolicy.isAllowed("intent://open#Intent;action=android.intent.action.VIEW;end"))
    }

    @Test
    fun rejectsDataScheme() {
        assertFalse(LinkSchemePolicy.isAllowed("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))
    }

    @Test
    fun rejectsUnknownCustomSchemes() {
        assertFalse(LinkSchemePolicy.isAllowed("myapp://open/something"))
        assertFalse(LinkSchemePolicy.isAllowed("orsik://pair?server=x&code=ABCDEFGHIJKLMNOPQRSTUVWX"))
    }

    @Test
    fun rejectsPlainHttp() {
        // Only https is allowlisted; plain http is not treated as safe.
        assertFalse(LinkSchemePolicy.isAllowed("http://example.com"))
    }

    @Test
    fun rejectsOtherKnownSchemes() {
        assertFalse(LinkSchemePolicy.isAllowed("mailto:someone@example.com"))
        assertFalse(LinkSchemePolicy.isAllowed("tel:+1234567890"))
    }

    @Test
    fun rejectsSchemelessRelativeTargets() {
        assertFalse(LinkSchemePolicy.isAllowed("example.com/path"))
        assertFalse(LinkSchemePolicy.isAllowed("/relative/path"))
        assertFalse(LinkSchemePolicy.isAllowed("./relative"))
    }

    @Test
    fun rejectsEmptyAndMalformedInputs() {
        assertFalse(LinkSchemePolicy.isAllowed(""))
        assertFalse(LinkSchemePolicy.isAllowed("://nonsense"))
        assertFalse(LinkSchemePolicy.isAllowed(":starts-with-colon"))
        // A scheme must start with a letter, so a digit-leading prefix is not a scheme.
        assertFalse(LinkSchemePolicy.isAllowed("123abc:thing"))
    }
}
