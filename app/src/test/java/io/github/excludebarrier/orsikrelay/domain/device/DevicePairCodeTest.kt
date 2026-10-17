package io.github.excludebarrier.orsikrelay.domain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure, local normalization/validation of the pairing code.
 *
 * Contract (read-only reference, server contract v1): 24 characters from
 * the alphabet `A-Z` without `I,L,O,U` plus `1-9`; displayed in 6 groups
 * of 4 characters separated by dashes (29 characters total). The server
 * normalizes (uppercase, removes dashes/blanks/underscores) and discards
 * everything else; the client mirrors that locally.
 *
 * [CODE] is a valid 24-character fixture: 22 letters (A-Z without
 * I,L,O,U) followed by two digits.
 */
class DevicePairCodeTest {

    @Test
    fun fullyDashedValidCodeNormalizesToCanonical() {
        val dashed = CODE.chunked(4).joinToString("-")
        assertEquals(29, dashed.length)
        assertEquals(CODE, DevicePairCode.normalize(dashed))
    }

    @Test
    fun caseInsensitiveAndTrimsSurroundingWhitespace() {
        val dashed = CODE.chunked(4).joinToString("-")
        assertEquals(CODE, DevicePairCode.normalize("  " + dashed.lowercase() + "\n"))
    }

    @Test
    fun underscoresAndSpacesAreStripped() {
        assertEquals(CODE, DevicePairCode.normalize(CODE.chunked(4).joinToString("_")))
        assertEquals(CODE, DevicePairCode.normalize(CODE.chunked(4).joinToString(" ")))
    }

    @Test
    fun bareCodeWithoutSeparatorsIsAccepted() {
        assertEquals(CODE, DevicePairCode.normalize(CODE))
        assertEquals(CODE, DevicePairCode.normalize(CODE.lowercase()))
    }

    @Test
    fun tooShortIsRejected() {
        assertNull(DevicePairCode.normalize(CODE.dropLast(1))) // 23
    }

    @Test
    fun tooLongIsRejected() {
        assertNull(DevicePairCode.normalize(CODE + "3")) // 25
    }

    @Test
    fun emptyAndBlankAndOnlySeparatorsAreRejected() {
        assertNull(DevicePairCode.normalize(""))
        assertNull(DevicePairCode.normalize("   "))
        assertNull(DevicePairCode.normalize("---- ---- ---- ---- ---- ----"))
    }

    @Test
    fun forbiddenLettersAreRejected() {
        for (forbidden in charArrayOf('I', 'L', 'O', 'U')) {
            val bad = CODE.substring(0, 1) + forbidden + CODE.substring(2)
            assertEquals(24, bad.length)
            assertNull("expected $forbidden to be rejected", DevicePairCode.normalize(bad))
        }
    }

    @Test
    fun zeroDigitIsRejected() {
        val idx = CODE.indexOfFirst { it.isDigit() }
        val bad = CODE.substring(0, idx) + '0' + CODE.substring(idx + 1)
        assertNull(DevicePairCode.normalize(bad))
    }

    @Test
    fun symbolsAreRejected() {
        val bad = CODE.substring(0, 3) + "!" + CODE.substring(4)
        assertNull(DevicePairCode.normalize(bad))
    }

    @Test
    fun canonicalLengthIsTwentyFour() {
        assertEquals(24, DevicePairCode.CANONICAL_LENGTH)
    }

    private companion object {
        // 22 letters: A-Z without I,L,O,U; plus digits 1,2. Length 24.
        const val CODE = "ABCDEFGHJKMNPQRSTVWXYZ12"
    }
}
