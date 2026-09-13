package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Pure, local normalization and validation of a device pairing code
 * (Mission 017A). No network access, no I/O, no error text.
 *
 * Server contract v1 (read-only reference, documented in internal task log): the
 * code is 24 characters from the alphabet `A-Z` without `I, L, O, U`
 * plus `1-9`, displayed in 6 groups of 4 characters separated by dashes
 * (29 characters total). The server normalizes (uppercase, removes
 * dashes/blanks/underscores) and discards everything else.
 *
 * The client mirrors that normalization locally so a malformed code
 * never reaches the wire:
 * - trim surrounding whitespace;
 * - uppercase;
 * - remove dashes, underscores and all whitespace (the display
 *   separators the operator or the user may have copied);
 * - require EXACTLY 24 characters, each from the closed alphabet.
 *
 * [normalize] returns the canonical 24-character code or null; it never
 * throws and never echoes the input.
 */
object DevicePairCode {

    /** The number of canonical characters after normalization. */
    const val CANONICAL_LENGTH = 24

    /**
     * Normalizes and validates [raw].
     *
     * @return the canonical 24-character code, or null when the input
     * does not normalize to exactly the closed alphabet/length.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().uppercase()
        if (trimmed.length > DISPLAYED_MAX_LENGTH) {
            return null
        }
        val compact = buildString {
            for (character in trimmed) {
                if (character == DASH || character == UNDERSCORE || character.isWhitespace()) {
                    continue
                }
                append(character)
            }
        }
        if (compact.length != CANONICAL_LENGTH) {
            return null
        }
        for (character in compact) {
            if (!ALPHABET.contains(character)) {
                return null
            }
        }
        return compact
    }

    private const val DASH = '-'
    private const val UNDERSCORE = '_'

    /**
     * A real code is 24 characters plus up to 5 display separators (29).
     * The guard is deliberately generous (64) so a pasted code with
     * extra copied whitespace is still accepted locally: the exact
     * 24-character closed check after compacting is what rejects
     * malformed codes.
     */
    private const val DISPLAYED_MAX_LENGTH = 64

    private val ALPHABET: Set<Char> =
        buildSet {
            for (codePoint in 65..90) {
                val letter = codePoint.toChar()
                if (letter in setOf('I', 'L', 'O', 'U')) continue
                add(letter)
            }
            for (digit in '1'..'9') add(digit)
        }
}
