package io.github.excludebarrier.orsikrelay.data.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Closed outcome of one bounded attachment read.
 *
 * [TooLarge] is a distinct outcome from [Unreadable] on purpose: the UI must
 * report an over-limit attachment as "too large" even when the provider never
 * reported a size and the limit was only hit while reading.
 */
sealed interface BoundedReadOutcome {
    /** At least one byte was read and the stream stayed within the bound. */
    class Bytes(val bytes: ByteArray) : BoundedReadOutcome {
        override fun equals(other: Any?): Boolean =
            other is Bytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Bytes(" + bytes.size + ")"
    }

    /** The stream delivered more than the bound: nothing is returned. */
    data object TooLarge : BoundedReadOutcome

    /** Empty stream, or the read failed (I/O error, provider refused). */
    data object Unreadable : BoundedReadOutcome
}

/**
 * Reads an attachment stream with a hard byte bound, without ever buffering
 * more than the bound. Pure Kotlin (no Android types) so the real production
 * path is unit-testable; [AndroidChatAttachmentSource] calls exactly this.
 *
 * Rules:
 * - the read never buffers more than [maxBytes] delivered bytes (peak memory is
 *   a small constant factor of that bound: the accumulating buffer plus its
 *   final copy); the read aborts with
 *   [BoundedReadOutcome.TooLarge] as soon as one byte more is seen, so an
 *   over-sized (or lying) source can never be partially accepted;
 * - an empty stream is [BoundedReadOutcome.Unreadable];
 * - any I/O failure is [BoundedReadOutcome.Unreadable];
 * - a stalled stream (a non-conforming source that reports "0 bytes" for a
 *   non-empty buffer) is [BoundedReadOutcome.Unreadable] too, never a
 *   truncated [BoundedReadOutcome.Bytes];
 * - the stream is always closed, on every path.
 */
object BoundedAttachmentReader {

    private const val CHUNK_BYTES = 64 * 1024

    fun read(stream: InputStream, maxBytes: Long): BoundedReadOutcome = try {
        stream.use { source ->
            // Enforced inside `use` so the stream is closed even for a
            // nonsensical bound.
            require(maxBytes >= 0L) { "maxBytes must not be negative" }
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = source.read(chunk)
                if (read < 0) break
                // A conforming stream never reports "0 bytes" for a non-empty
                // buffer. If it does, the source is stalled: fail closed
                // instead of accepting whatever was buffered so far as a
                // complete attachment.
                if (read == 0) return BoundedReadOutcome.Unreadable
                total += read
                if (total > maxBytes) return BoundedReadOutcome.TooLarge
                buffer.write(chunk, 0, read)
            }
            val bytes = buffer.toByteArray()
            if (bytes.isEmpty()) BoundedReadOutcome.Unreadable else BoundedReadOutcome.Bytes(bytes)
        }
    } catch (_: Exception) {
        BoundedReadOutcome.Unreadable
    } catch (_: OutOfMemoryError) {
        BoundedReadOutcome.Unreadable
    }
}

/**
 * Builds the inline image data URL the server accepts for `image_url` content
 * parts. `Base64.getEncoder()` never inserts line breaks, so the payload has no
 * whitespace artefacts; the MIME type comes from the validated attachment, not
 * from provider text.
 */
object AttachmentDataUrl {
    private const val PREFIX = "data:"
    private const val BASE64_MARKER = ";base64,"

    fun inline(mimeType: String, bytes: ByteArray): String =
        PREFIX + mimeType + BASE64_MARKER + java.util.Base64.getEncoder().encodeToString(bytes)
}
