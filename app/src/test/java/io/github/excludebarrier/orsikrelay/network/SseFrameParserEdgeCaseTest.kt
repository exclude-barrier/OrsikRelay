package io.github.excludebarrier.orsikrelay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adversarial framing inputs for [SseFrameParser]: every chunk boundary,
 * mixed line endings, comments, field shapes, exact size limits, a leading
 * BOM and the terminal `done` cutoff.
 */
class SseFrameParserEdgeCaseTest {

    @Test
    fun splitAtEveryPossibleChunkBoundaryProducesTheSameFrame() {
        val text = "event: a\r\ndata: ä😀\r\n\r\n"
        val expected = listOf(ParsedSseFrame("a", "ä😀"))

        for (split in 0..text.length) {
            val parser = SseFrameParser()
            val first = parser.feed(text.substring(0, split))
            val second = parser.feed(text.substring(split))
            assertEquals("split at index $split", expected, first + second)
            parser.endOfStream()
        }
    }

    @Test
    fun charByCharFeedOfAFragmentedFrameProducesOneFrame() {
        val parser = SseFrameParser()
        val collected = mutableListOf<ParsedSseFrame>()
        for (character in "event: run.started\r\ndata: {\"a\":1}\r\n\r\n") {
            collected += parser.feed(character.toString())
        }

        assertEquals(
            listOf(ParsedSseFrame("run.started", "{\"a\":1}")),
            collected
        )
        parser.endOfStream()
    }

    @Test
    fun emptyChunksNeverAdvanceOrBreakTheParserState() {
        val parser = SseFrameParser()
        assertTrue(parser.feed("event: a").isEmpty())
        assertTrue(parser.feed("").isEmpty())
        assertTrue(parser.feed("").isEmpty())
        assertEquals(
            listOf(ParsedSseFrame("a", "x")),
            parser.feed("\ndata: x\n\n")
        )
        parser.endOfStream()
    }

    @Test
    fun crlfSplitAcrossAnEmptyChunkIsStillOneLineBreak() {
        // A `\r` ends a chunk, an empty chunk carries no decision, and the
        // `\n` opening the next chunk is the second half of the same CRLF.
        val parser = SseFrameParser()
        val first = parser.feed("event: a\r")
        val empty = parser.feed("")
        val newline = parser.feed("\n")
        val rest = parser.feed("data: x\n\n")

        assertTrue(first.isEmpty())
        assertTrue(empty.isEmpty())
        assertTrue(newline.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "x")), rest)
        parser.endOfStream()
    }

    @Test
    fun loneCrFollowedByOrdinaryTextDoesNotSwallowTheText() {
        val parser = SseFrameParser()
        assertTrue(parser.feed("event: a\rd").isEmpty())
        assertEquals(
            listOf(ParsedSseFrame("a", "x")),
            parser.feed("ata: x\n\n")
        )
    }

    @Test
    fun mixedLfCrlfAndLoneCrInOneStreamDispatchBothFrames() {
        val frames = SseFrameParser().feed(
            // A lone CR ends the first frame's blank line; then LF and CRLF.
            "event: a\rdata: one\r\n\r" +
                "event: b\ndata: two\r\n\r\n"
        )

        assertEquals(
            listOf(ParsedSseFrame("a", "one"), ParsedSseFrame("b", "two")),
            frames
        )
    }

    @Test
    fun commentsAreIgnoredBetweenAndInsideFrames() {
        val parser = SseFrameParser()
        val first = parser.feed(":keepalive\n\n")
        val second = parser.feed(
            ": keepalive before the frame\n" +
                "event: a\n" +
                ": comment between fields\n" +
                "data: x\n" +
                ":trailing comment\n" +
                "\n"
        )

        assertTrue(first.isEmpty())
        assertEquals(listOf(ParsedSseFrame("a", "x")), second)
        parser.endOfStream()
    }

    @Test
    fun commentOnlyStreamAtFrameBoundariesProducesNoFrames() {
        val parser = SseFrameParser()
        val frames = parser.feed(": keepalive\r\n\r\n:another\r\n\r\n")

        assertTrue(frames.isEmpty())
        parser.endOfStream()
    }

    @Test
    fun dataWithoutEventAndEventWithoutDataKeepTheirNullSide() {
        val parser = SseFrameParser()
        val frames = parser.feed(
            "data: payload\n\n" +
                "event: with-empty-data\ndata:\n\n" +
                "event: without-data\n\n"
        )

        assertEquals(
            listOf(
                ParsedSseFrame(null, "payload"),
                ParsedSseFrame("with-empty-data", ""),
                ParsedSseFrame("without-data", null)
            ),
            frames
        )
    }

    @Test
    fun fieldLineWithoutColonHasAnEmptyValue() {
        // SSE semantics: a line without a colon is a field name with an
        // empty value.
        val frames = SseFrameParser().feed("event\ndata\n\n")

        assertEquals(listOf(ParsedSseFrame("", "")), frames)
    }

    @Test
    fun lineAtTheExactByteLimitIsAcceptedAndOneByteOverIsRejected() {
        // "data: " is 6 bytes, so 10 value bytes fill a 16-byte line.
        val accepted = SseFrameParser(maxLineBytes = 16)
        assertEquals(
            listOf(ParsedSseFrame(null, "x".repeat(10))),
            accepted.feed("data: " + "x".repeat(10) + "\n\n")
        )

        assertFramingViolation {
            SseFrameParser(maxLineBytes = 16)
                .feed("data: " + "x".repeat(11) + "\n\n")
        }
    }

    @Test
    fun lineLimitCountsUtf8BytesWhereTheCodeUnitBudgetStillFits() {
        // "data: " (6) + five `ä` (10) = exactly 16 UTF-8 bytes, but only
        // 11 UTF-16 code units: the exact byte check, not the conservative
        // code-unit budget, must decide.
        val accepted = SseFrameParser(maxLineBytes = 16)
        assertEquals(
            listOf(ParsedSseFrame(null, "ä".repeat(5))),
            accepted.feed("data: " + "ä".repeat(5) + "\n\n")
        )

        // 12 code units still fit the buffer, but 18 UTF-8 bytes exceed
        // the line limit.
        assertFramingViolation {
            SseFrameParser(maxLineBytes = 16)
                .feed("data: " + "ä".repeat(6) + "\n\n")
        }
    }

    @Test
    fun conservativeCodeUnitBufferBoundRejectsBeforeAPathologicalLineGrows() {
        assertFramingViolation {
            SseFrameParser(maxLineBytes = 4).feed("data: x")
        }
    }

    @Test
    fun framePayloadLimitCountsDataBytesPlusExactlyOneSeparatorPerJoin() {
        // "a", "b" and "c" cost 1 + (1 + 1) + (1 + 1) = 5 bytes.
        val exact = SseFrameParser(maxFrameBytes = 5)
        assertEquals(
            listOf(ParsedSseFrame(null, "a\nb\nc")),
            exact.feed("data: a\ndata: b\ndata: c\n\n")
        )

        // A fourth data line adds one byte of content plus one separator.
        assertFramingViolation {
            SseFrameParser(maxFrameBytes = 5)
                .feed("data: a\ndata: b\ndata: c\ndata: d\n\n")
        }
    }

    @Test
    fun eventValueBoundIsExactAt4096Utf8Bytes() {
        val allowed = "x".repeat(4096)
        assertEquals(
            listOf(ParsedSseFrame(allowed, null)),
            SseFrameParser().feed("event: $allowed\n\n")
        )

        assertFramingViolation {
            SseFrameParser().feed("event: " + "x".repeat(4097) + "\n\n")
        }
    }

    @Test
    fun duplicateEventFieldIsRejectedEvenAfterDataLines() {
        assertFramingViolation {
            SseFrameParser().feed("data: x\nevent: a\nevent: b\n\n")
        }
    }

    @Test
    fun unknownOrMisCasedFieldNamesAreRejectedClosed() {
        listOf("id: 7", "retry: 500", "Event: a").forEach { line ->
            assertFramingViolation {
                SseFrameParser().feed("data: x\n$line\n\n")
            }
        }
    }

    @Test
    fun leadingBomAtStreamStartIsIgnored() {
        // WHATWG Server-sent events: "One leading U+FEFF BYTE ORDER MARK
        // character must be ignored if any are present."
        // SUSPECTED BUG: the parser keeps the BOM as part of the first
        // field name ("\uFEFFevent"), treats it as an unknown field and
        // rejects the whole stream.
        val frames = SseFrameParser().feed("\uFEFFevent: run.started\ndata: {}\n\n")

        assertEquals(listOf(ParsedSseFrame("run.started", "{}")), frames)
    }

    @Test
    fun terminalFrameStopsParsingAndIgnoresEverythingAfterIt() {
        val parser = SseFrameParser(maxLineBytes = 64, terminalEventName = "done")

        assertEquals(
            listOf(ParsedSseFrame("done", "{}")),
            parser.feed("event: done\ndata: {}\n\n")
        )
        // Everything after the terminal frame is semantically outside the
        // turn: no framing violation may be raised, no frame may escape.
        assertTrue(parser.feed("id: 1\nretry: 5\n\n").isEmpty())
        assertTrue(parser.feed("event: " + "x".repeat(500) + "\n\n").isEmpty())
        assertTrue(parser.feed(": unterminated comment").isEmpty())
        parser.endOfStream()
    }

    @Test
    fun terminalNameOnlyClosesTheTurnAfterThatExactEventWasDispatched() {
        val parser = SseFrameParser(terminalEventName = "done")

        assertEquals(
            listOf(ParsedSseFrame("run.started", "{}")),
            parser.feed("event: run.started\ndata: {}\n\n")
        )
        assertEquals(
            listOf(ParsedSseFrame("done", "{}")),
            parser.feed("event: done\ndata: {}\n\n")
        )
        assertTrue(parser.feed("event: run.started\ndata: {}\n\n").isEmpty())
        parser.endOfStream()
    }

    private inline fun assertFramingViolation(block: () -> Unit) {
        try {
            block()
            fail("expected SseFrameParserException")
        } catch (expected: SseFrameParserException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }
}
