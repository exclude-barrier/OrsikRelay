package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * Pure, local validation of the outgoing chat message (Mission 006A).
 * No network access, no I/O.
 *
 * Rules:
 * - the message must not be blank after trimming;
 * - at most [MAX_MESSAGE_LENGTH] characters after trimming;
 * - no NUL character;
 * - no ISO control characters except line feed (`\n`) and tab (`\t`);
 * - the returned value is the trimmed message; inner line breaks are
 *   preserved.
 *
 * Failure messages are stable, generic reasons. They never echo user
 * input, so no message content can leak through them.
 */
object ChatMessageValidator {

    const val MAX_MESSAGE_LENGTH = 32_768

    /**
     * Validates [raw] and returns the trimmed message on success.
     */
    fun validate(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("message must not be blank")
            )
        }
        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            return Result.failure(
                IllegalArgumentException("message exceeds maximum length")
            )
        }
        for (character in trimmed) {
            if (character == '\u0000' ||
                (character.isISOControl() && character != '\n' && character != '\t')
            ) {
                return Result.failure(
                    IllegalArgumentException("message contains disallowed control characters")
                )
            }
        }
        return Result.success(trimmed)
    }
}
