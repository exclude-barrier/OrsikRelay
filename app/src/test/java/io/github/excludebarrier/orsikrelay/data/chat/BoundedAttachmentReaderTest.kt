package io.github.excludebarrier.orsikrelay.data.chat

import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the real bounded-read path used by
 * [io.github.excludebarrier.orsikrelay.platform.attachment.AndroidChatAttachmentSource].
 *
 * The reader is pure Kotlin on purpose, so the production code is tested here
 * instead of a duplicated test-only reimplementation.
 */
class BoundedAttachmentReaderTest {

    private companion object {
        const val LIMIT = 5L * 1024L * 1024L
    }

    /** Records how many bytes the reader actually pulled and whether it was closed. */
    private class TrackingStream(
        private val chunks: List<ByteArray>,
        private val failAfterChunks: Int? = null,
        private val shortReads: Boolean = false
    ) : InputStream() {
        var bytesServed = 0L
            private set
        var closed = false
            private set
        private var index = 0

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) == 1) buffer[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (failAfterChunks != null && index >= failAfterChunks) throw IOException("provider gone")
            if (index >= chunks.size) return -1
            val chunk = chunks[index]
            index++
            val allowed = if (shortReads) minOf(len, 7) else len
            val size = minOf(chunk.size, allowed)
            System.arraycopy(chunk, 0, b, off, size)
            bytesServed += size
            return size
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `empty stream is unreadable`() {
        val stream = TrackingStream(emptyList())
        assertEquals(BoundedReadOutcome.Unreadable, BoundedAttachmentReader.read(stream, LIMIT))
        assertTrue("stream must be closed", stream.closed)
    }

    @Test
    fun `small file is returned unchanged`() {
        val payload = ByteArray(1024) { it.toByte() }
        val stream = TrackingStream(listOf(payload))
        val outcome = BoundedAttachmentReader.read(stream, LIMIT)
        assertEquals(BoundedReadOutcome.Bytes(payload), outcome)
        assertTrue(stream.closed)
    }

    @Test
    fun `exactly the limit is accepted`() {
        val payload = ByteArray(4096) { 7 }
        val stream = TrackingStream(listOf(payload))
        val outcome = BoundedAttachmentReader.read(stream, payload.size.toLong())
        assertEquals(BoundedReadOutcome.Bytes(payload), outcome)
        assertTrue(stream.closed)
    }

    @Test
    fun `one byte over the limit is rejected without partial success`() {
        val payload = ByteArray(4097) { 7 }
        val stream = TrackingStream(listOf(payload))
        val outcome = BoundedAttachmentReader.read(stream, 4096L)
        assertEquals(BoundedReadOutcome.TooLarge, outcome)
        assertTrue("stream must be closed", stream.closed)
    }

    @Test
    fun `unbounded source is aborted early instead of buffered`() {
        // A source that never ends: the reader must stop at the bound, so it can
        // only ever have served bound + one chunk.
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        val outcome = BoundedAttachmentReader.read(endless, LIMIT)
        assertEquals(BoundedReadOutcome.TooLarge, outcome)
        assertTrue(
            "read " + endless.served + " bytes for a " + LIMIT + " byte bound",
            endless.served <= LIMIT + 64L * 1024L
        )
    }

    @Test
    fun `stream that lies about its size is caught while reading`() {
        // Provider reported 1 KiB in metadata, stream keeps delivering: the bound
        // is enforced on the stream, never on the reported size.
        val stream = TrackingStream(listOf(ByteArray(64 * 1024), ByteArray(64 * 1024)))
        val outcome = BoundedAttachmentReader.read(stream, 64L * 1024L)
        assertEquals(BoundedReadOutcome.TooLarge, outcome)
    }

    @Test
    fun `io failure mid stream is unreadable and not a partial success`() {
        val stream = TrackingStream(listOf(ByteArray(2048)), failAfterChunks = 1)
        val outcome = BoundedAttachmentReader.read(stream, LIMIT)
        assertEquals(BoundedReadOutcome.Unreadable, outcome)
        assertTrue(stream.closed)
    }

    @Test
    fun `many small reads are assembled in order`() {
        val payload = ByteArray(500) { (it % 251).toByte() }
        val chunks = payload.toList().chunked(3).map { it.toByteArray() }
        val stream = TrackingStream(chunks, shortReads = true)
        val outcome = BoundedAttachmentReader.read(stream, LIMIT)
        assertEquals(BoundedReadOutcome.Bytes(payload), outcome)
    }

    @Test
    fun `unusual chunk sizes across the bound are handled`() {
        // One byte under the limit, then a chunk that crosses it.
        val first = ByteArray(4095) { 1 }
        val second = ByteArray(64) { 2 }
        val stream = TrackingStream(listOf(first, second))
        assertEquals(BoundedReadOutcome.TooLarge, BoundedAttachmentReader.read(stream, 4096L))

        val fitting = TrackingStream(listOf(first, ByteArray(1) { 3 }))
        assertEquals(
            BoundedReadOutcome.Bytes(first + byteArrayOf(3)),
            BoundedAttachmentReader.read(fitting, 4096L)
        )
    }

    @Test
    fun `a stream that never makes progress is abandoned`() {
        // Defensive: a provider stream that keeps reporting zero bytes must not
        // spin forever and must not look like a successful read.
        val stalled = object : InputStream() {
            var calls = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                return 0
            }
        }
        assertEquals(BoundedReadOutcome.Unreadable, BoundedAttachmentReader.read(stalled, LIMIT))
        assertTrue("read must stop, calls=" + stalled.calls, stalled.calls <= 2)
    }

    @Test
    fun `stalled stream after partial bytes is unreadable not a truncated success`() {
        // A conforming stream never reports "0 bytes" for a non-empty buffer.
        // If one does, the bytes delivered before the stall must NOT be
        // accepted as a complete attachment.
        val partial = object : InputStream() {
            var calls = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                if (calls == 1) {
                    b[off] = 5
                    return 1
                }
                return 0
            }
        }
        assertEquals(BoundedReadOutcome.Unreadable, BoundedAttachmentReader.read(partial, LIMIT))
        assertTrue("read must stop, calls=" + partial.calls, partial.calls <= 2)
    }

    @Test
    fun `negative bound is refused without leaking the stream`() {
        val stream = TrackingStream(listOf(ByteArray(8)))
        assertEquals(BoundedReadOutcome.Unreadable, BoundedAttachmentReader.read(stream, -1L))
        assertTrue("stream must still be closed", stream.closed)
    }

    @Test
    fun `stream is closed on success oversize and failure`() {
        val ok = TrackingStream(listOf(ByteArray(16)))
        BoundedAttachmentReader.read(ok, LIMIT)
        val tooLarge = TrackingStream(listOf(ByteArray(64)))
        BoundedAttachmentReader.read(tooLarge, 8L)
        val failing = TrackingStream(listOf(ByteArray(16)), failAfterChunks = 0)
        BoundedAttachmentReader.read(failing, LIMIT)
        assertTrue("success path must close", ok.closed)
        assertTrue("oversize path must close", tooLarge.closed)
        assertTrue("failure path must close", failing.closed)
    }
}

/**
 * The fail-closed default of the closed read outcome. Every source that
 * does not override `readInlineDataUrlResult` (including
 * [io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource]) must map a
 * successful URL to `DataUrl` and every `null` to `Unreadable`.
 */
class ChatAttachmentSourceDefaultResultTest {

    private class UrlOnlySource(private val url: String?) : ChatAttachmentSource {
        override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? = null
        override suspend fun readInlineDataUrl(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): String? = url
    }

    @Test
    fun `default maps a url to DataUrl`() = kotlinx.coroutines.runBlocking {
        val result = UrlOnlySource("data:image/png;base64,AA==")
            .readInlineDataUrlResult("content://p/1", "image/png", 10L)
        assertEquals(
            ChatAttachmentReadResult.DataUrl("data:image/png;base64,AA=="),
            result
        )
    }

    @Test
    fun `default maps null to Unreadable and never to TooLarge`() = kotlinx.coroutines.runBlocking {
        assertEquals(
            ChatAttachmentReadResult.Unreadable,
            UrlOnlySource(null).readInlineDataUrlResult("content://p/1", "image/png", 10L)
        )
        assertEquals(
            ChatAttachmentReadResult.Unreadable,
            NoopChatAttachmentSource.readInlineDataUrlResult("content://p/1", "image/png", 10L)
        )
    }
}

/**
 * The inline data URL the wire contract requires, for every MIME type the
 * picker gate accepts.
 */
class AttachmentDataUrlTest {

    private fun decode(url: String): ByteArray =
        Base64.getDecoder().decode(url.substringAfter(";base64,"))

    @Test
    fun `jpeg png and webp use the exact inline prefix`() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(payload),
            AttachmentDataUrl.inline("image/jpeg", payload))
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(payload),
            AttachmentDataUrl.inline("image/png", payload))
        assertEquals("data:image/webp;base64," + Base64.getEncoder().encodeToString(payload),
            AttachmentDataUrl.inline("image/webp", payload))
    }

    @Test
    fun `payload decodes back without whitespace artefacts`() {
        val payload = ByteArray(4096) { (it * 31 % 256).toByte() }
        val url = AttachmentDataUrl.inline("image/png", payload)
        assertTrue("no line breaks", !url.contains("\n") && !url.contains("\r"))
        assertTrue("no whitespace", !url.contains(" ") && !url.contains("\t"))
        assertEquals(4096, decode(url).size)
        assertEquals(BoundedReadOutcome.Bytes(payload), BoundedReadOutcome.Bytes(decode(url)))
    }

    @Test
    fun `url carries no local uri path or display name`() {
        val url = AttachmentDataUrl.inline("image/jpeg", ByteArray(64) { 9 })
        assertTrue("no content uri", !url.contains("content://"))
        assertTrue("no file uri", !url.contains("file://"))
        assertTrue("no filesystem path", !url.contains("/storage/") && !url.contains("/sdcard/"))
        assertTrue("no provider text", !url.contains("com.android.providers"))
    }
}
