package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial [SecretValue] and [ProfileDraft] representation cases: the raw
 * secret must never appear in a string, in equality output or in a debug
 * rendering, whatever characters it contains.
 */
class SecretValueEdgeCaseTest {

    private val syntheticSecret = "sk-edge-0123456789ABCDEF\u0000\u007f"

    @Test
    fun `toString is redacted for unicode control and very long secrets`() {
        val values = listOf(
            syntheticSecret,
            "ümlaut-ä-ö-✓",
            "\u0000\u0001\u001f\u007f",
            "a".repeat(10_000)
        )

        values.forEach { raw ->
            val rendered = SecretValue(raw).toString()
            assertEquals("[REDACTED]", rendered)
            assertFalse(rendered.contains(raw))
        }
    }

    @Test
    fun `equals is identity based and never reveals the raw value`() {
        val first = SecretValue(syntheticSecret)
        val sameRaw = SecretValue(syntheticSecret)
        val other = SecretValue("different-value")

        assertTrue(first == first)
        assertTrue(first.equals(first))
        // The wrapper is deliberately not a data class: two instances with the
        // same raw value are NOT equal, so equality never turns into a
        // secret-comparison oracle.
        assertFalse(first == sameRaw)
        assertFalse(first.equals(sameRaw))
        assertFalse(first.equals(other))
        assertFalse(first.equals(null))
        assertFalse(first.equals(syntheticSecret))
    }

    @Test
    fun `equality output is redacted on both equal and unequal paths`() {
        val first = SecretValue(syntheticSecret)
        val sameRaw = SecretValue(syntheticSecret)

        // JUnit's assertion messages render operands through toString(); both
        // the pass and the fail path must stay redacted.
        assertEquals(first, first)
        assertFalse(first == sameRaw)
        assertFalse(first.toString().contains(syntheticSecret))
        assertFalse(sameRaw.toString().contains(syntheticSecret))
    }

    @Test
    fun `hash based rendering of the secret as a map key is redacted`() {
        val secret = SecretValue(syntheticSecret)
        val rendered = mapOf(secret to "value").toString()

        assertTrue(rendered.contains("[REDACTED]"))
        assertFalse(rendered.contains(syntheticSecret))
    }

    @Test
    fun `isEmpty reflects the exact raw value including whitespace only`() {
        assertTrue(SecretValue("").isEmpty())
        assertFalse(SecretValue(" ").isEmpty())
        assertFalse(SecretValue("\t").isEmpty())
        assertFalse(SecretValue(syntheticSecret).isEmpty())
    }

    @Test
    fun `profile draft toString is redacted for control and unicode secrets`() {
        listOf(
            syntheticSecret,
            "ümlaut-ä-ö-✓",
            "a".repeat(1_000)
        ).forEach { raw ->
            val draft = ProfileDraft(
                profileName = "Edge",
                baseUrl = "https://example.org/p/edge",
                apiKey = SecretValue(raw)
            )
            val rendered = draft.toString()

            assertTrue(rendered.contains("apiKey=[REDACTED]"))
            assertFalse(rendered.contains(raw))
        }
    }
}
