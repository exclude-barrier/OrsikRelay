package io.github.excludebarrier.orsikrelay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SseFrameParserTest {

    @Test
    fun lfFrameProducesNameAndData() {
        val frames = SseFrameParser().feed("event: run.started\ndata: {\"a\":1}\n\n")

        assertEquals(listOf(ParsedSseFrame("run.started", "{\"a\":1}")), frames)
    }

    @Test
    fun crlfAndLoneCrLineBreaksAreAccepted() {
        val crlf = SseFrameParser().feed("event: a\r\ndata: x\r\n\r\n")
        assertEquals(listOf(ParsedSseFrame("a", "x")), crlf)
        val loneCr = SseFrameParser().feed("event: a\rdata: x\r\r")
        assertEquals(listOf(ParsedSseFrame("a", "x")), loneCr)
    }

    @Test
    fun keepaliveCommentFrameProducesNoFrame() {
        val parser = SseFrameParser()
        val first = parser.feed(": keepalive\n\n")
        val second = parser.feed(": another comment\nevent: run.started\ndata: {\"ok\":true}\n\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("run.started", "{\"ok\":true}")), second)
    }

    @Test
    fun multipleDataLinesAreJoinedWithNewline() {
        val frames = SseFrameParser().feed("event: a\ndata: line-one\ndata: line-two\n\n")

        assertEquals(listOf(ParsedSseFrame("a", "line-one\nline-two")), frames)
    }

    @Test
    fun frameWithoutNameAndFrameWithoutDataAreAllowed() {
        val nameless = SseFrameParser().feed("data: {\"a\":1}\n\n")
        assertEquals(listOf(ParsedSseFrame(null, "{\"a\":1}")), nameless)
        val dataless = SseFrameParser().feed("event: done\n\n")
        assertEquals(listOf(ParsedSseFrame("done", null)), dataless)
    }

    @Test
    fun namelessFrameDoesNotTerminateParserWithoutConfiguredTerminalName() {
        val parser = SseFrameParser()

        val frames = parser.feed(
            "data: first\n\n" +
                "event: run.started\ndata: second\n\n"
        )

        assertEquals(
            listOf(
                ParsedSseFrame(null, "first"),
                ParsedSseFrame("run.started", "second")
            ),
            frames
        )
    }

    @Test
    fun fragmentedChunksAcrossFeedsProduceOneFrame() {
        val parser = SseFrameParser()
        val first = parser.feed("eve")
        val second = parser.feed("nt: run.started\nda")
        val third = parser.feed("ta: {\"session_id\":\"synthetic-session\"}")
        val fourth = parser.feed("\n\n")

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertTrue(third.isEmpty())
        assertEquals(
            listOf(ParsedSseFrame("run.started", "{\"session_id\":\"synthetic-session\"}")),
            fourth
        )
    }

    @Test
    fun emptyLineBoundaryBetweenFramesDispatchesBoth() {
        val frames = SseFrameParser()
            .feed("event: a\ndata: one\n\nevent: b\ndata: two\n\n")

        assertEquals(listOf(ParsedSseFrame("a", "one"), ParsedSseFrame("b", "two")), frames)
    }

    @Test
    fun extraSpacesAfterColonArePartOfTheValue() {
        val frames = SseFrameParser().feed("event: a\ndata:  two-spaces\n\n")

        assertEquals(listOf(ParsedSseFrame("a", " two-spaces")), frames)
    }

    @Test
    fun unknownFieldIsRejected() {
        val parser = SseFrameParser()
        try {
            parser.feed("retry: 500\nevent: a\ndata: x\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun oversizedLineIsRejected() {
        val parser = SseFrameParser(maxLineBytes = 16)
        try {
            parser.feed("event: " + "x".repeat(20) + "\n\ndata: a\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            // closed error without dynamic data
        }
    }

    @Test
    fun oversizedDataPayloadIsRejected() {
        val parser = SseFrameParser(maxFrameBytes = 16)
        try {
            parser.feed("event: a\ndata: " + "x".repeat(20) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            // closed error without dynamic data
        }
    }

    @Test
    fun incompleteFrameAtEndOfStreamIsRejected() {
        val parser = SseFrameParser()
        parser.feed("event: run.started\ndata: {\"a\":1}\n")
        try {
            parser.endOfStream()
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            // closed error without dynamic data
        }
    }

    @Test
    fun cleanEndOfStreamIsAccepted() {
        val parser = SseFrameParser()
        parser.feed("event: a\ndata: x\n\n")
        parser.endOfStream()
    }

    @Test
    fun pendingLineWithoutBoundaryAtEndOfStreamIsRejected() {
        val parser = SseFrameParser()
        parser.feed("event: a\n")
        try {
            parser.endOfStream()
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            // closed error without dynamic data
        }
    }

    @Test
    fun emptyLineWithoutAnyFieldsIsSilent() {
        val frames = SseFrameParser().feed("\n\n\n")
        assertTrue(frames.isEmpty())
    }

    @Test
    fun crlfSplitAcrossChunkBoundaryOnFieldLineIsOneLine() {
        // The CRLF pair may be split by the network between two `feed`
        // chunks: the `\r` ends one chunk, the `\n` opens the next. The
        // following LF must not be treated as an additional empty line.
        val parser = SseFrameParser()
        val first = parser.feed("event: run.started\r")
        val second = parser.feed("\ndata: {\"a\":1}\n\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("run.started", "{\"a\":1}")), second)
        parser.endOfStream()
    }

    @Test
    fun crlfSplitAcrossChunkBoundaryOnEmptyFrameBoundary() {
        // A frame boundary is an empty line; with CRLF that empty line is
        // itself two line breaks. If the chunk boundary splits the first
        // CRLF, the next chunk starts with the LF of that same empty
        // line and must not dispatch a phantom empty frame.
        val parser = SseFrameParser()
        val first = parser.feed("event: a\ndata: one\r")
        val second = parser.feed("\n\nevent: b\ndata: two\n\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "one"), ParsedSseFrame("b", "two")), second)
        parser.endOfStream()
    }

    @Test
    fun loneCrSplitAcrossChunkBoundaryIsOneLine() {
        val parser = SseFrameParser()
        val first = parser.feed("event: a\r")
        val second = parser.feed("data: x\n\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "x")), second)
    }

    @Test
    fun loneCrMidChunkAndSplitAcrossChunksProduceExactlyOneFrame() {
        // A `\r` not immediately followed by `\n` is a complete line
        // break on its own: mid-chunk it never swallows a later `\n`,
        // and split across two `feed` calls it must produce exactly
        // one frame (no phantom empty frame, no merged line).
        val midChunk = SseFrameParser().feed("event: a\rdata: x\n\n")
        assertEquals(listOf(ParsedSseFrame("a", "x")), midChunk)

        val split = SseFrameParser()
        val first = split.feed("event: a\r")
        val second = split.feed("data: x\n\n")
        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "x")), second)
        split.endOfStream()
    }

    @Test
    fun crlfSplitAcrossChunkBoundaryStaysOneLine() {
        // Regression guard: a CRLF pair split across two `feed` chunks
        // remains one line break (the `\n` opening the next chunk is
        // the second half of the pair, not an extra empty line).
        val parser = SseFrameParser()
        val first = parser.feed("event: a\r")
        val second = parser.feed("\ndata: x\n\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "x")), second)
        parser.endOfStream()
    }

    @Test
    fun lineLimitCountsExactUtf8BytesNotUtf16Characters() {
        // `ä` is one UTF-16 character but two UTF-8 bytes. With a 16 byte
        // line limit, 4 `ä` after "event: " (7 bytes) = 15 bytes and is
        // allowed, 5 `ä` = 17 bytes and must be rejected.
        val accepted = SseFrameParser(maxLineBytes = 16)
        val frames = accepted.feed("event: " + "ä".repeat(4) + "\ndata: x\n\n")
        assertEquals(listOf(ParsedSseFrame("ä".repeat(4), "x")), frames)

        val rejected = SseFrameParser(maxLineBytes = 16)
        try {
            rejected.feed("event: " + "ä".repeat(5) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }

        // ASCII boundary: exactly 16 bytes allowed, 17 rejected.
        val asciiOk = SseFrameParser(maxLineBytes = 16)
        val asciiFrames = asciiOk.feed("event: " + "x".repeat(9) + "\ndata: y\n\n")
        assertEquals(listOf(ParsedSseFrame("x".repeat(9), "y")), asciiFrames)
        val asciiBad = SseFrameParser(maxLineBytes = 16)
        try {
            asciiBad.feed("event: " + "x".repeat(10) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun surrogatePairCountsExactFourUtf8BytesEvenAcrossFeeds() {
        // `😀` is one code point encoded in four UTF-8 bytes, but two
        // UTF-16 code units (a high and a low surrogate). The high
        // surrogate may end one chunk and the low surrogate open the
        // next: the line must still be measured in exact UTF-8 bytes.
        // With maxLineBytes = 16 and "event: " (7 bytes), two `😀`
        // (8 bytes) = 15 bytes is allowed and three `😀` (12 bytes) =
        // 19 bytes must be rejected.
        val accepted = SseFrameParser(maxLineBytes = 16)
        val frames = accepted.feed("event: " + "😀".repeat(2) + "\ndata: x\n\n")
        assertEquals(listOf(ParsedSseFrame("😀".repeat(2), "x")), frames)

        val rejected = SseFrameParser(maxLineBytes = 16)
        try {
            rejected.feed("event: " + "😀".repeat(3) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }

        // Split across two `feed` calls between the high and the low
        // surrogate of the first `😀`: same result, same limit.
        val splitAccepted = SseFrameParser(maxLineBytes = 16)
        val first = splitAccepted.feed("event: " + "\uD83D")
        val second = splitAccepted.feed("\uDE00" + "😀" + "\ndata: x\n\n")
        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("😀".repeat(2), "x")), second)

        val splitRejected = SseFrameParser(maxLineBytes = 16)
        splitRejected.feed("event: " + "\uD83D")
        try {
            splitRejected.feed("\uDE00" + "😀".repeat(2) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun unpairedSurrogatesAreRejected() {
        val highOnly = SseFrameParser()
        try {
            highOnly.feed("event: " + "\uD83D" + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
        val lowOnly = SseFrameParser()
        try {
            lowOnly.feed("data: " + "\uDE00" + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun frameLimitIsExactUtf8DataBytesWithNMinusOneSeparators() {
        // The payload is the sum of the exact UTF-8 sizes of the `data:`
        // values plus exactly one newline separator between N data lines
        // (N - 1 bytes). `ä` is 2 bytes, so with maxFrameBytes = 16 the
        // boundary is 8 `ä` (16 bytes) allowed, 9 `ä` (18 bytes)
        // rejected.
        val exact = SseFrameParser(maxFrameBytes = 16)
        val frames = exact.feed("event: a\ndata: " + "ä".repeat(8) + "\n\n")
        assertEquals(listOf(ParsedSseFrame("a", "ä".repeat(8))), frames)

        val over = SseFrameParser(maxFrameBytes = 16)
        try {
            over.feed("event: a\ndata: " + "ä".repeat(9) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun frameLimitWithMultipleDataLinesCountsDataPlusSeparators() {
        // With maxFrameBytes = 8: "x" (1 byte) + "aaaaa" (5 bytes) plus
        // exactly one separator between the two lines = 7 bytes, allowed.
        // One more data line adds its bytes plus a second separator and
        // exceeds the limit.
        val exact = SseFrameParser(maxFrameBytes = 8)
        val frames = exact.feed("data: x\ndata: aaaaa\n\n")
        assertEquals(listOf(ParsedSseFrame(null, "x\naaaaa")), frames)

        val over = SseFrameParser(maxFrameBytes = 8)
        try {
            over.feed("data: x\ndata: aaaaa\ndata: b\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }

        // The separator itself counts: "x" (1) + "aaaa" (4) + 1 =
        // exactly 8 is allowed while "x" + "aaaaa" (5) + 1 = 9 is not.
        val separatorCounts = SseFrameParser(maxFrameBytes = 8)
        val separatorFrames = separatorCounts.feed("data: x\ndata: aaaa\n\n")
        assertEquals(listOf(ParsedSseFrame(null, "x\naaaa")), separatorFrames)
    }

    @Test
    fun frameLimitCountsSurrogatePairsAsFourUtf8Bytes() {
        // `😀` is 4 UTF-8 bytes (two UTF-16 code units). With
        // maxFrameBytes = 8 exactly two `😀` fit; three do not.
        val exact = SseFrameParser(maxFrameBytes = 8)
        val frames = exact.feed("event: a\ndata: " + "😀".repeat(2) + "\n\n")
        assertEquals(listOf(ParsedSseFrame("a", "😀".repeat(2))), frames)

        val over = SseFrameParser(maxFrameBytes = 8)
        try {
            over.feed("event: a\ndata: " + "😀".repeat(3) + "\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun duplicateEventFieldIsRejectedClosed() {
        // The confirmed framing carries exactly one `event:` field per
        // frame; two `event:` fields are ambiguous and rejected closed.
        val parser = SseFrameParser()
        try {
            parser.feed("event: a\nevent: b\ndata: x\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }

        // Also rejected when the first field has no colon (whole line is
        // the field name) and the second one sets a value.
        val parser2 = SseFrameParser()
        try {
            parser2.feed("event\nevent: b\ndata: x\n\n")
            fail("expected framing violation")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun defaultBoundsAcceptAFinalAnswerFrameAboveTheOldLineLimit() {
        // A long final `assistant.completed` answer arrives on one data line;
        // the default bound matches the bridge relay bound (1 MiB).
        val longLine = "a".repeat(300 * 1024)
        val frames = SseFrameParser().feed("event: assistant.completed\ndata: $longLine\n\n")
        assertEquals(1, frames.size)
    }
}
