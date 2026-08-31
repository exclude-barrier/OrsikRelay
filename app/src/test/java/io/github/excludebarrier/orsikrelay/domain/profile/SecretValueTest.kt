package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the in-memory API-Key wrapper of Mission 002A.
 *
 * Pflichtfall 11: Die Secret-Darstellung ist `[REDACTED]` und enthält
 * nie den Rohwert. Der Testwert ist ein synthetischer Platzhalter,
 * kein echter Schlüssel.
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

        // Ganzer Rohwert sowie alle Teilstücke (Fenster 3..len) fehlen.
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
