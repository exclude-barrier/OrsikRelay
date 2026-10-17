package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure onboarding-form validation.
 *
 * All values are synthetic test fixtures; no network, no
 * real hosts or keys.
 */
class ProfileFormValidatorTest {

    private val validName = "Android"
    private val validUrl = "https://example.org/p/android"
    private val syntheticKey = SecretValue("sk-test-0123456789abcdef")

    /** Extracts the typed [ProfileFormValidator.ValidationError] or null. */
    private fun Result<ProfileDraft>.error(): ProfileFormValidator.ValidationError? =
        exceptionOrNull() as? ProfileFormValidator.ValidationError

    // Required case 3: empty profile name.
    @Test
    fun `empty profile name is rejected`() {
        val result = ProfileFormValidator.validate("", validUrl, syntheticKey)

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.PROFILE_NAME_EMPTY,
            result.error()?.profileNameError
        )
    }

    @Test
    fun `whitespace only profile name is rejected`() {
        val result = ProfileFormValidator.validate("   ", validUrl, syntheticKey)

        assertTrue(result.isFailure)
        assertNotNull(result.error()?.profileNameError)
    }

    // Required case 4: profile name longer than 64 characters.
    @Test
    fun `profile name longer than 64 characters is rejected`() {
        val result = ProfileFormValidator.validate("a".repeat(65), validUrl, syntheticKey)

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.PROFILE_NAME_TOO_LONG,
            result.error()?.profileNameError
        )
    }

    @Test
    fun `profile name with exactly 64 characters is accepted`() {
        val result = ProfileFormValidator.validate("a".repeat(64), validUrl, syntheticKey)

        assertTrue(result.isSuccess)
        assertEquals("a".repeat(64), result.getOrThrow().profileName)
    }

    @Test
    fun `profile name is trimmed before validation and stored`() {
        val result = ProfileFormValidator.validate("  Android  ", validUrl, syntheticKey)

        assertTrue(result.isSuccess)
        assertEquals("Android", result.getOrThrow().profileName)
    }

    // Required case 10: empty API key.
    @Test
    fun `empty api key is rejected`() {
        val result = ProfileFormValidator.validate(validName, validUrl, SecretValue(""))

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.API_KEY_EMPTY,
            result.error()?.apiKeyError
        )
    }

    // URL errors are reported per field.
    @Test
    fun `invalid url is rejected with base url error only`() {
        val result = ProfileFormValidator.validate(validName, "http://example.org", syntheticKey)

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.BASE_URL_INVALID,
            result.error()?.baseUrlError
        )
        assertNull(result.error()?.profileNameError)
        assertNull(result.error()?.apiKeyError)
    }

    @Test
    fun `all invalid fields produce all three error codes`() {
        val result = ProfileFormValidator.validate("", "http://", SecretValue(""))
        val error = result.error()

        assertTrue(result.isFailure)
        assertNotNull(error?.profileNameError)
        assertNotNull(error?.baseUrlError)
        assertNotNull(error?.apiKeyError)
    }

    // Valid input produces a draft with a normalized URL.
    @Test
    fun `valid input produces draft with normalized url`() {
        val result = ProfileFormValidator.validate(
            validName,
            "https://example.org/p/android/",
            syntheticKey
        )

        assertTrue(result.isSuccess)
        val draft = result.getOrThrow()
        assertEquals(validName, draft.profileName)
        assertEquals("https://example.org/p/android", draft.baseUrl)
    }

    // The pairing mode validates only the shared connection
    // fields and must never be blocked by the API-key rule.
    @Test
    fun `connection fields validate without the api key rule`() {
        val result = ProfileFormValidator.validateConnectionFields(validName, validUrl)

        assertTrue(result.isSuccess)
        val draft = result.getOrThrow()
        assertEquals(validName, draft.profileName)
        assertEquals(validUrl, draft.baseUrl)
        // The pairing mode stores no credential; the slot stays empty.
        assertTrue(draft.apiKey.isEmpty())
    }

    @Test
    fun `connection fields still reject invalid name and url`() {
        val nameError = ProfileFormValidator
            .validateConnectionFields("", validUrl)
            .error()
        assertTrue(nameError?.profileNameError != null)

        val urlError = ProfileFormValidator
            .validateConnectionFields(validName, "http://example.org")
            .error()
        assertEquals(
            ProfileFormValidator.ErrorCodes.BASE_URL_INVALID,
            urlError?.baseUrlError
        )
        assertNull(urlError?.profileNameError)
        // The pairing mode has no API-key field, so no key error.
        assertNull(urlError?.apiKeyError)
    }

    // Security design: no toString that exposes the API key.
    @Test
    fun `draft toString never exposes the raw api key`() {
        val result = ProfileFormValidator.validate(validName, validUrl, syntheticKey)
        val rendered = result.getOrThrow().toString()

        assertFalse(rendered.contains("sk-test-0123456789abcdef"))
        assertTrue(rendered.contains("[REDACTED]"))
    }
}
