package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure base-URL validation.
 *
 * All hosts are synthetic test fixtures; no network access, no real
 * endpoints or credentials.
 */
class ProfileBaseUrlValidatorTest {

    // Required case 1: valid HTTPS origin.
    @Test
    fun `valid https origin is accepted`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org", result.getOrThrow())
    }

    // Required case 2: valid HTTPS URL with /p/<profile> and normalization.
    @Test
    fun `valid https url with profile prefix is normalized`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org/p/android/")

        assertTrue(result.isSuccess)
        assertEquals("https://example.org/p/android", result.getOrThrow())
    }

    // Required case 5: HTTP is rejected.
    @Test
    fun `http scheme is rejected`() {
        val result = ProfileBaseUrlValidator.validate("http://example.org")

        assertTrue(result.isFailure)
    }

    // Required case 6: missing host is rejected.
    @Test
    fun `missing host is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://")

        assertTrue(result.isFailure)
    }

    // Required case 7: userinfo is rejected.
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

    // Required case 8: query is rejected.
    @Test
    fun `query is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org?token=abc")

        assertTrue(result.isFailure)
    }

    // Required case 9: fragment is rejected.
    @Test
    fun `fragment is rejected`() {
        val result = ProfileBaseUrlValidator.validate("https://example.org#section")

        assertTrue(result.isFailure)
    }

    // Additional cases: normalization.
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

    // Additional cases: rejection.
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
