package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure base-URL validation of Mission 002A.
 *
 * All hosts are synthetic test fixtures; no network access, no real
 * endpoints or credentials.
 */
class ProfileBaseUrlValidatorTest {

    // Pflichtfall 1: gültige HTTPS-Origin.
    @Test
    fun `valid https origin is accepted`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org", result.getOrThrow())
    }

    // Pflichtfall 2: gültige HTTPS-URL mit /p/<profile> und Normalisierung.
    @Test
    fun `valid https url with profile prefix is normalized`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org/p/android/")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org/p/android", result.getOrThrow())
    }

    // Pflichtfall 5: HTTP wird abgelehnt.
    @Test
    fun `http scheme is rejected`() {
        val result = ProfileBaseUrlValidator.validate("http://example.org")

        assertTrue(result.isFailure)
    }

    // Pflichtfall 6: fehlender Host wird abgelehnt.
    @Test
    fun `missing host is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://")

        assertTrue(result.isFailure)
    }

    // Pflichtfall 7: Userinfo wird abgelehnt.
    @Test
    fun `userinfo with password is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://user:pass@example.org")

        assertTrue(result.isFailure)
    }

    @Test
    fun `userinfo without password is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://user@example.org")

        assertTrue(result.isFailure)
    }

    // Pflichtfall 8: Query wird abgelehnt.
    @Test
    fun `query is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org?token=abc")

        assertTrue(result.isFailure)
    }

    // Pflichtfall 9: Fragment wird abgelehnt.
    @Test
    fun `fragment is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org#section")

        assertTrue(result.isFailure)
    }

    // Zusatzfälle: Normalisierung.
    @Test
    fun `origin root with trailing slash stays at origin root`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org/")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org", result.getOrThrow())
    }

    @Test
    fun `https url with port and path is accepted`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org:8443/p/android")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org:8443/p/android", result.getOrThrow())
    }

    // Zusatzfälle: Ablehnung.
    @Test
    fun `non https absolute scheme is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("ftp://example.org").isFailure)
    }

    @Test
    fun `relative url without scheme is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("example.org").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("/p/android").isFailure)
    }

    @Test
    fun `query and fragment combination is rejected`() {
        assertTrue(
            ProfileBaseUrlValidator.validate("https://example.org/p/android?x=1#y").isFailure
        )
    }

    @Test
    fun `unparsable url is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("https://ex ample.org").isFailure)
    }

    @Test
    fun `empty url is rejected`() {
        assertTrue(ProfileBaseUrlValidator.validate("").isFailure)
        assertTrue(ProfileBaseUrlValidator.validate("   ").isFailure)
    }
}
