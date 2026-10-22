package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial base-URL cases: IP literal spellings, ports, IDN/punycode,
 * percent-encoded paths, dot segments, whitespace/control characters and
 * very long inputs.
 *
 * All hosts are synthetic test fixtures; no network access.
 */
class ProfileBaseUrlValidatorEdgeCaseTest {

    // -- scheme --------------------------------------------------------------

    @Test
    fun `uppercase and mixed-case HTTPS schemes are accepted and normalized to lowercase`() {
        assertEquals(
            "https://Example.ORG/Path",
            ProfileBaseUrlValidator.validate("HTTPS://Example.ORG/Path/").getOrThrow()
        )
        assertEquals(
            "https://example.org",
            ProfileBaseUrlValidator.validate("HtTpS://example.org").getOrThrow()
        )
    }

    @Test
    fun `non-HTTPS schemes are rejected regardless of case`() {
        listOf(
            "http://example.org",
            "HTTP://example.org",
            "ftp://example.org",
            "wss://example.org",
            "file:///tmp/x"
        ).forEach { raw ->
            assertTrue("must reject $raw", ProfileBaseUrlValidator.validate(raw).isFailure)
        }
    }

    // -- IP literals ---------------------------------------------------------

    @Test
    fun `IPv4 literal is accepted unchanged`() {
        assertEquals(
            "https://127.0.0.1/p/android",
            ProfileBaseUrlValidator.validate("https://127.0.0.1/p/android").getOrThrow()
        )
    }

    @Test
    fun `bracketed IPv6 literal with port and path is accepted unchanged`() {
        assertEquals(
            "https://[::1]",
            ProfileBaseUrlValidator.validate("https://[::1]").getOrThrow()
        )
        assertEquals(
            "https://[2001:db8::1]:8443/p/android",
            ProfileBaseUrlValidator.validate("https://[2001:db8::1]:8443/p/android").getOrThrow()
        )
    }

    @Test
    fun `unbracketed IPv6 literal is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("https://::1").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://::1/p/android").isFailure)
    }

    @Test
    fun `zero-padded octal-like and decimal IPv4 spellings are accepted unchanged`() {
        // The validator does not canonicalize non-standard IPv4 spellings; it
        // only requires an https host. These are kept verbatim so the caller
        // can see exactly what was typed.
        listOf(
            "https://010.0.0.1",
            "https://0177.0.0.1",
            "https://2130706433"
        ).forEach { raw ->
            assertEquals(raw, ProfileBaseUrlValidator.validate(raw).getOrThrow())
        }
    }

    @Test
    fun `hexadecimal IPv4 spelling is rejected because java net URI exposes no host`() {
        // Fail closed: java.net.URI does not treat the hex spelling as a host,
        // so the validator never accepts an ambiguous address form.
        assertTrue(ProfileBaseUrlValidator.validate("https://0x7f.0.0.1").isFailure)
    }

    // -- ports ---------------------------------------------------------------

    @Test
    fun `valid boundary ports are accepted and preserved exactly`() {
        assertEquals(
            "https://example.org:1",
            ProfileBaseUrlValidator.validate("https://example.org:1").getOrThrow()
        )
        assertEquals(
            "https://example.org:65535",
            ProfileBaseUrlValidator.validate("https://example.org:65535").getOrThrow()
        )
        assertEquals(
            "https://example.org:00080",
            ProfileBaseUrlValidator.validate("https://example.org:00080").getOrThrow()
        )
    }

    @Test
    fun `port zero is rejected`() {
        // SUSPECTED BUG: port 0 is accepted and preserved, although no HTTPS
        // server can be reached on port 0.
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org:0").isFailure)
    }

    @Test
    fun `port above 65535 is rejected`() {
        // SUSPECTED BUG: port 65536 is accepted and preserved even though it
        // does not fit the 16-bit TCP port range.
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org:65536").isFailure)
    }

    @Test
    fun `non-numeric port is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org:abc").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org:-1").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org:+80").isFailure)
    }

    // -- host forms ----------------------------------------------------------

    @Test
    fun `trailing root dot is accepted and preserved`() {
        assertEquals(
            "https://example.org.",
            ProfileBaseUrlValidator.validate("https://example.org.").getOrThrow()
        )
        assertEquals(
            "https://example.org./p/android",
            ProfileBaseUrlValidator.validate("https://example.org./p/android").getOrThrow()
        )
    }

    @Test
    fun `punycode host is accepted unchanged`() {
        assertEquals(
            "https://xn--bcher-kva.example/p/android",
            ProfileBaseUrlValidator.validate("https://xn--bcher-kva.example/p/android").getOrThrow()
        )
    }

    @Test
    fun `raw Unicode host is rejected by the URI host parser`() {
        // Current, documented limitation: callers must supply the punycode
        // form. java.net.URI parses the authority but exposes no host for a
        // raw U-label, so the validator rejects it as hostless.
        assertTrue(ProfileBaseUrlValidator.validate("https://bücher.example").isFailure)
    }

    @Test
    fun `userinfo is rejected in every spelling`() {
        listOf(
            "https://user:pass@example.org",
            "https://user@example.org",
            "https://user:@example.org",
            "https://@example.org",
            "https://example.org@evil.example",
            "https://user%3Apass@example.org"
        ).forEach { raw ->
            assertTrue("must reject userinfo in $raw", ProfileBaseUrlValidator.validate(raw).isFailure)
        }
    }

    @Test
    fun `empty query and empty fragment markers are rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org?").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org#").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org/p?").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("https://example.org/p#").isFailure)
    }

    // -- paths and percent encoding -----------------------------------------

    @Test
    fun `literal dot segments are preserved`() {
        // The validator does not resolve dot segments; the HTTP client sees the
        // path exactly as entered.
        assertEquals(
            "https://example.org/p/../admin",
            ProfileBaseUrlValidator.validate("https://example.org/p/../admin").getOrThrow()
        )
        assertEquals(
            "https://example.org/a/./b",
            ProfileBaseUrlValidator.validate("https://example.org/a/./b").getOrThrow()
        )
    }

    @Test
    fun `percent-encoded space in the path is preserved`() {
        // SUSPECTED BUG: normalize() uses the decoded uri.path, so %20 turns
        // into a literal space and the result is not a valid URL string.
        assertEquals(
            "https://example.org/p/a%20b",
            ProfileBaseUrlValidator.validate("https://example.org/p/a%20b").getOrThrow()
        )
    }

    @Test
    fun `percent-encoded path separator is preserved`() {
        // SUSPECTED BUG: %2F is decoded to "/", silently changing the path
        // structure (one segment becomes two).
        assertEquals(
            "https://example.org/p/a%2Fb",
            ProfileBaseUrlValidator.validate("https://example.org/p/a%2Fb").getOrThrow()
        )
    }

    @Test
    fun `double-encoded percent sequence is not decoded a layer`() {
        // SUSPECTED BUG: %2520 becomes %20, so the normalized value no longer
        // denotes the same resource as the input.
        assertEquals(
            "https://example.org/p/a%2520b",
            ProfileBaseUrlValidator.validate("https://example.org/p/a%2520b").getOrThrow()
        )
    }

    @Test
    fun `percent-encoded dot segment stays encoded`() {
        // SUSPECTED BUG: %2e%2e is decoded to "..", introducing a dot segment
        // the user did not write.
        assertEquals(
            "https://example.org/p/%2e%2e/admin",
            ProfileBaseUrlValidator.validate("https://example.org/p/%2e%2e/admin").getOrThrow()
        )
    }

    @Test
    fun `multiple trailing slashes are collapsed but internal slashes are kept`() {
        assertEquals(
            "https://example.org//p//x",
            ProfileBaseUrlValidator.validate("https://example.org//p//x//").getOrThrow()
        )
    }

    // -- whitespace, control characters, size -------------------------------

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        assertEquals(
            "https://example.org/p",
            ProfileBaseUrlValidator.validate("  \t https://example.org/p \n ").getOrThrow()
        )
    }

    @Test
    fun `embedded whitespace and control characters are rejected`() {
        listOf(
            "https://example.org/p/a b",
            "https://example.org/p/a\tb",
            "https://example.org/p/a\nb",
            "https://example.org/p/a\u0000b",
            "https://example.org/p/a\u007fb",
            "https://exa\u0000mple.org"
        ).forEach { raw ->
            assertTrue(
                "must reject whitespace/control characters in $raw",
                ProfileBaseUrlValidator.validate(raw).isFailure
            )
        }
    }

    @Test
    fun `very long path is accepted without truncation`() {
        val path = "a".repeat(10_000)
        val raw = "https://example.org/p/$path"

        val result = ProfileBaseUrlValidator.validate(raw)

        assertTrue(result.isSuccess)
        assertEquals(raw, result.getOrThrow())
    }

    @Test
    fun `empty and whitespace-only urls are rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate(" \t\n ").isFailure)
    }
}
