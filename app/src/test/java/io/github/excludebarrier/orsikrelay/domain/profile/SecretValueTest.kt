package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the in-memory API-key wrapper.
 *
 * Required case 11: the secret representation is `[REDACTED]` and never
 * contains the raw value. The test value is a synthetic placeholder,
 * not a real key.
 */
class SecretValueTest {

    private val syntheticRaw = "sk-test-0123456789abcdef"

    @Test
    fun `toString is exactly redacted`() {
        val secret = SecretValue(syntheticRaw)

        assertEquals("[REDACTED]", secret.toString())
    }

    @Test
    fun `toString never contains any part of the raw value`() {
        val secret = SecretValue(syntheticRaw)
        val rendered = secret.toString()

        // The whole raw value and all partial pieces (window 3..len) are absent.
        assertFalse(rendered.contains(syntheticRaw))
        for (length in 3..syntheticRaw.length) {
            for (start in 0..syntheticRaw.length - length) {
                val fragment = syntheticRaw.substring(start, start + length)
                assertFalse(
                    "Redacted output must not contain any part of the raw value",
                    rendered.contains(fragment)
                )
            }
        }
    }

    @Test
    fun `toString is redacted even for empty secret`() {
        assertEquals("[REDACTED]", SecretValue("").toString())
    }

    @Test
    fun `isEmpty reflects the raw value`() {
        assertFalse(SecretValue(syntheticRaw).isEmpty())
        assertTrue(SecretValue("").isEmpty())
    }
}
