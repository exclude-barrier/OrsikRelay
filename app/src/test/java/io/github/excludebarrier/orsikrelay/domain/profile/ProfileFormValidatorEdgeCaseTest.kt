package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial onboarding-form cases: control characters and extreme lengths
 * in the profile name, pathological URLs, and the guarantee that validation
 * errors never echo user input.
 */
class ProfileFormValidatorEdgeCaseTest {

    private val validUrl = "https://example.org/p/edge"
    private val syntheticKey = SecretValue("sk-edge-0123456789abcdef")

    private fun Result<ProfileDraft>.validationError(): ProfileFormValidator.ValidationError? =
        exceptionOrNull() as? ProfileFormValidator.ValidationError

    @Test
    fun `profile names containing control characters are rejected`() {
        // SUSPECTED BUG: control characters are not whitespace, so trim() keeps
        // them and the name is accepted; such names reach the UI, notifications
        // and the persisted metadata record.
        listOf(
            "Edge\u0000Name",
            "Line\nBreak",
            "Bell\u0007Name",
            "Tab\tInside",
            "Delete\u007fName"
        ).forEach { name ->
            val result = ProfileFormValidator.validate(name, validUrl, syntheticKey)

            assertTrue("control characters must be rejected in $name", result.isFailure)
            assertNotNull(result.validationError()?.profileNameError)
        }
    }

    @Test
    fun `profile name of one hundred thousand characters is rejected without crashing`() {
        val result = ProfileFormValidator.validate("a".repeat(100_000), validUrl, syntheticKey)

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.PROFILE_NAME_TOO_LONG,
            result.validationError()?.profileNameError
        )
    }

    @Test
    fun `profile name of exactly the maximum length after trimming is accepted`() {
        val name = "a".repeat(ProfileFormValidator.PROFILE_NAME_MAX_LENGTH)
        val result = ProfileFormValidator.validate("  $name  ", validUrl, syntheticKey)

        assertTrue(result.isSuccess)
        assertEquals(name, result.getOrThrow().profileName)
    }

    @Test
    fun `one character over the maximum after trimming is rejected`() {
        val result = ProfileFormValidator.validate(
            "  " + "a".repeat(ProfileFormValidator.PROFILE_NAME_MAX_LENGTH + 1) + "  ",
            validUrl,
            syntheticKey
        )

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.PROFILE_NAME_TOO_LONG,
            result.validationError()?.profileNameError
        )
    }

    @Test
    fun `whitespace-only profile names are empty after trimming`() {
        listOf(" ", "\t", "\n", " \t\r\n ").forEach { name ->
            val result = ProfileFormValidator.validate(name, validUrl, syntheticKey)

            assertTrue(result.isFailure)
            assertEquals(
                ProfileFormValidator.ErrorCodes.PROFILE_NAME_EMPTY,
                result.validationError()?.profileNameError
            )
        }
    }

    @Test
    fun `surrounding whitespace in the base url is trimmed and the path normalized`() {
        val result = ProfileFormValidator.validate(
            "Edge",
            "  \t https://example.org/p/edge/ \n ",
            syntheticKey
        )

        assertTrue(result.isSuccess)
        assertEquals("https://example.org/p/edge", result.getOrThrow().baseUrl)
    }

    @Test
    fun `base urls with embedded control characters are rejected`() {
        listOf(
            "https://example.org/p/a b",
            "https://example.org/p/a\tb",
            "https://example.org/p/a\nb",
            "https://example.org/p/a\u0000b"
        ).forEach { url ->
            val result = ProfileFormValidator.validate("Edge", url, syntheticKey)

            assertTrue(result.isFailure)
            assertEquals(
                ProfileFormValidator.ErrorCodes.BASE_URL_INVALID,
                result.validationError()?.baseUrlError
            )
        }
    }

    @Test
    fun `all three fields can fail at their extreme boundaries`() {
        val result = ProfileFormValidator.validate(
            "a".repeat(100_000),
            "h".repeat(100_000),
            SecretValue("")
        )
        val error = result.validationError()

        assertTrue(result.isFailure)
        assertEquals(
            ProfileFormValidator.ErrorCodes.PROFILE_NAME_TOO_LONG,
            error?.profileNameError
        )
        assertEquals(ProfileFormValidator.ErrorCodes.BASE_URL_INVALID, error?.baseUrlError)
        assertEquals(ProfileFormValidator.ErrorCodes.API_KEY_EMPTY, error?.apiKeyError)
    }

    @Test
    fun `validation errors never contain the name url or secret`() {
        val name = "Edge-Name"
        val url = "http://example.org/secret-path"
        val rawKey = "sk-edge-secret-value"

        val result = ProfileFormValidator.validate(name, url, SecretValue(rawKey))
        val error = result.validationError()

        assertTrue(result.isFailure)
        assertNotNull(error)
        val rendered = error!!.toString()
        assertFalse(rendered.contains(name))
        assertFalse(rendered.contains(url))
        assertFalse(rendered.contains(rawKey))
        assertEquals("onboarding form validation failed", error.message)
    }

    @Test
    fun `pairing validation shares the profile name rules`() {
        // SUSPECTED BUG: validateConnectionFields() also accepts control
        // characters in the profile name, so the pairing path inherits the same
        // gap as the API-key path.
        val result = ProfileFormValidator.validateConnectionFields(
            "Edge\u0000Name",
            validUrl
        )

        assertTrue(result.isFailure)
        assertNotNull(result.validationError()?.profileNameError)
    }

    @Test
    fun `a valid name and url still produce an empty credential slot in pairing mode`() {
        val result = ProfileFormValidator.validateConnectionFields("Edge", validUrl)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().apiKey.isEmpty())
        assertNull(result.exceptionOrNull())
    }
}
