package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamResumeState
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adversarial payload and lifecycle inputs for [ChatStreamEventPipeline]:
 * approval shapes, unknown and misordered events, malformed JSON, exact size
 * bounds, the bounded duplicate window and the one dynamic string that
 * crosses the boundary.
 */
class ChatStreamEventPipelineEdgeCaseTest {

    @Test
    fun assistantCompletedWithExplicitNullContentFallsBackToComposedDeltas() {
        val pipeline = startedPipeline()
        pipeline.process(deltaFrame(3, MSG_ID, "Hal"))
        pipeline.process(deltaFrame(4, MSG_ID, "lo"))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("Hallo")),
            pipeline.process(
                ParsedSseFrame(
                    "assistant.completed",
                    baseFrame(5)
                        .put("message_id", MSG_ID)
                        .put("content", JSONObject.NULL)
                        .toString()
                )
            )
        )
    }

    @Test
    fun assistantCompletedWithoutDeltasOrContentIsAnEmptyFinalText() {
        val pipeline = startedPipeline()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("")),
            pipeline.process(
                ParsedSseFrame(
                    "assistant.completed",
                    baseFrame(3).put("message_id", MSG_ID).toString()
                )
            )
        )
    }

    @Test
    fun approvalRequestWithOnlyProtocolFieldsProducesTheClosedEvent() {
        val pipeline = approvalPipeline()
        val events = pipeline.process(
            ParsedSseFrame("approval.request", approvalPayload(3).toString())
        )

        assertEquals(1, events.size)
        val request = (events.single() as ChatTurnEvent.ApprovalRequested).request
        assertEquals(APPROVAL_RUN_ID, request.runId)
        assertEquals(REQUEST_ID, request.requestId)
        assertEquals(ApprovalRequest.SAFE_DESCRIPTION, request.description)
    }

    @Test
    fun approvalRequestWithMissingExtraOrUnknownFieldsIsRejectedClosed() {
        val invalidPayloads = listOf(
            approvalPayload(3).also { it.remove("request_id") },
            approvalPayload(3).also { it.remove("description") },
            approvalPayload(3).put("description", "A dynamic server description"),
            approvalPayload(3).also { it.remove("choices") },
            approvalPayload(3).put("choices", "once"),
            approvalPayload(3).put("choices", JSONArray().put("once")),
            approvalPayload(3).put("choices", JSONArray().put("once").put("deny").put("once")),
            approvalPayload(3).put("choices", JSONArray().put("deny").put("once")),
            approvalPayload(3).put("choices", JSONArray().put("once").put("always")),
            approvalPayload(3).put("choices", JSONArray().put("once").put(JSONObject.NULL)),
            approvalPayload(3).put("request_id", "A".repeat(32)),
            approvalPayload(3).put("request_id", "a".repeat(31)),
            approvalPayload(3).put("request_id", "/etc/passwd"),
            approvalPayload(3).put("request_id", REQUEST_ID + "\u0085"),
            approvalPayload(3).put("command", "synthetic-command"),
            approvalPayload(3).put("run_id", "run_other")
        )

        invalidPayloads.forEachIndexed { index, payload ->
            val pipeline = approvalPipeline()
            assertProtocolViolation(
                pipeline,
                ParsedSseFrame("approval.request", payload.toString()),
                "approval fixture $index must be rejected"
            )
        }
    }

    @Test
    fun approvalRequestOutsideTheMessageWindowIsRejectedClosed() {
        val beforeMessage = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        beforeMessage.process(frame("run.started", 1, runId = APPROVAL_RUN_ID))
        assertProtocolViolation(
            beforeMessage,
            ParsedSseFrame("approval.request", approvalPayload(2).toString())
        )

        val afterCompletion = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        afterCompletion.process(frame("run.started", 1, runId = APPROVAL_RUN_ID))
        afterCompletion.process(messageStartedFrame(2, MSG_ID, APPROVAL_RUN_ID))
        afterCompletion.process(completedFrame(3, MSG_ID, "Hallo", APPROVAL_RUN_ID))
        assertProtocolViolation(
            afterCompletion,
            ParsedSseFrame("approval.request", approvalPayload(4).toString())
        )
    }

    @Test
    fun unknownEventTypesAreRejectedClosed() {
        listOf(
            "run.interrupted",
            "message.updated",
            "assistant.delta.extra",
            "done.extra",
            "RUN.STARTED"
        ).forEach { name ->
            val pipeline = startedPipeline()
            assertProtocolViolation(
                pipeline,
                ParsedSseFrame(name, baseFrame(3).toString()),
                "unknown event $name"
            )
        }
    }

    @Test
    fun aNewFrameAfterDoneIsRejectedButAnIdenticalDoneResubmissionIsDropped() {
        val pipeline = ChatStreamEventPipeline(SESSION_ID)
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(completedFrame(3, MSG_ID, "Hallo"))
        pipeline.process(frame("run.completed", 4))
        assertTrue(pipeline.process(frame("done", 5)).isEmpty())

        // The byte-identical resubmission of the confirmed `done` frame is
        // dropped, never applied twice.
        assertTrue(pipeline.process(frame("done", 5)).isEmpty())
        // Any *new* frame after `done` contradicts the stream order.
        assertProtocolViolation(pipeline, frame("done", 6))
    }

    @Test
    fun malformedJsonInTheDataFieldIsRejectedClosed() {
        listOf("not-json", "", "{", "[1,2]", "{\"session_id\":", "null").forEach { raw ->
            val pipeline = ChatStreamEventPipeline(SESSION_ID)
            assertProtocolViolation(
                pipeline,
                ParsedSseFrame("run.started", raw),
                "malformed JSON payload: $raw"
            )
        }
    }

    @Test
    fun sessionIdMustMatchExactlyIncludingCaseAndTrailingSpace() {
        listOf("Synthetic-session", "synthetic-session ", " synthetic-session").forEach { other ->
            val pipeline = startedPipeline()
            val frame = ParsedSseFrame(
                "assistant.delta",
                baseFrame(3)
                    .put("session_id", other)
                    .put("message_id", MSG_ID)
                    .put("delta", "x")
                    .toString()
            )
            assertProtocolViolation(pipeline, frame, "session_id=$other")
        }
    }

    @Test
    fun c1AndDelControlsAreRejectedInIdentifiers() {
        assertProtocolViolation(
            ChatStreamEventPipeline(SESSION_ID),
            frame("run.started", 1, JSONObject().put("run_id", "run\u0085id"))
        )
        assertProtocolViolation(
            ChatStreamEventPipeline(SESSION_ID),
            frame("run.started", 1, JSONObject().put("run_id", "run\u007fid"))
        )

        val badMessageId = startedPipeline()
        assertProtocolViolation(
            badMessageId,
            deltaFrame(3, "msg\u0085id", "x")
        )

        val badRunId = startedPipeline()
        assertProtocolViolation(
            badRunId,
            frame("assistant.delta", 3, JSONObject().put("run_id", "run\u007fid"))
        )
    }

    @Test
    fun duplicateWindowKeepsRecentFramesAndRejectsEvictedOnes() {
        // The bounded dedup window holds the most recent 64 confirmed
        // frames. A resubmission inside the window is dropped; one whose
        // digest was evicted is rejected closed as a backward sequence.
        val pipeline = ChatStreamEventPipeline(SESSION_ID)
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        for (seq in 3L..64L) {
            pipeline.process(deltaFrame(seq, MSG_ID, "d$seq"))
        }

        assertTrue(
            "the oldest frame still inside the 64-entry window must be dropped",
            pipeline.process(frame("run.started", 1)).isEmpty()
        )

        pipeline.process(deltaFrame(65, MSG_ID, "d65"))
        assertProtocolViolation(
            pipeline,
            frame("run.started", 1),
            "an evicted duplicate can no longer be proven identical"
        )
    }

    @Test
    fun aRejectedFrameNeverAdvancesTheSequenceState() {
        val pipeline = startedPipeline()

        // Valid sequence number, but the delta names another message: the
        // dispatch rejects it and must not consume seq 3.
        assertProtocolViolation(pipeline, deltaFrame(3, "other-message", "x"))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("ok")),
            pipeline.process(deltaFrame(3, MSG_ID, "ok"))
        )
        assertEquals(3L, pipeline.snapshot()?.lastSeq)

        // A gap must not consume anything either: the very next sequence
        // number is still accepted afterwards.
        val gapped = startedPipeline()
        assertProtocolViolation(gapped, deltaFrame(5, MSG_ID, "skip"))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("next")),
            gapped.process(deltaFrame(3, MSG_ID, "next"))
        )
    }

    @Test
    fun deltaLengthBoundaryIsExactAt32KiB() {
        val exact = startedPipeline()
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("x".repeat(32 * 1024))),
            exact.process(deltaFrame(3, MSG_ID, "x".repeat(32 * 1024)))
        )

        val over = startedPipeline()
        assertProtocolViolation(over, deltaFrame(3, MSG_ID, "x".repeat(32 * 1024 + 1)))
    }

    @Test
    fun composedTextLimitAppliesToResumedTextPlusTheNextDelta() {
        val atLimit = resumedWithComposed(MAX_COMPOSED - 1)
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("y")),
            atLimit.process(deltaFrame(3, MSG_ID, "y"))
        )

        val overLimit = resumedWithComposed(MAX_COMPOSED)
        assertProtocolViolation(
            overLimit,
            deltaFrame(3, MSG_ID, "y"),
            "one character past the composed-text limit must be rejected"
        )
    }

    @Test
    fun runCompletedDropsC0AndDelControlCharactersInPendingSteer() {
        listOf("ok\u0000bad", "ok\u007fbad", "   ", "\n\t").forEach { raw ->
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted(null)),
                completedPipeline().process(
                    frame("run.completed", 4, JSONObject().put("pending_steer", raw))
                )
            )
        }
    }

    @Test
    fun runCompletedDropsC1ControlCharactersInPendingSteer() {
        // The documented rule is "any control character other than CR, LF
        // and TAB is dropped"; C1 controls (U+0080..U+009F) are ISO control
        // characters just like DEL.
        // SUSPECTED BUG: safePendingSteer checks only `code < 0x20` and
        // `code == 0x7F`, so a C1 control such as U+0085 (NEXT LINE) is
        // propagated into the UI/composer text.
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted(null)),
            completedPipeline().process(
                frame("run.completed", 4, JSONObject().put("pending_steer", "ok\u0085bad"))
            )
        )
    }

    @Test
    fun toolRowsCarryingPayloadsOnlySurfaceASafeToolName() {
        val pipeline = startedPipeline()

        val events = pipeline.process(
            ParsedSseFrame(
                "tool.started",
                baseFrame(3)
                    .put("tool_name", "web_search")
                    .put("args", JSONObject().put("command", "SECRET-ARGS"))
                    .put("output", "SECRET-OUTPUT")
                    .put("path", "/SECRET/PATH")
                    .put("preview", "SECRET-PREVIEW")
                    .toString()
            )
        )
        assertEquals(listOf<ChatTurnEvent>(ChatTurnEvent.ToolActivity("web_search")), events)
        assertFalse(events.toString().contains("SECRET"))

        // An unusable identifier is dropped, the activity itself is kept.
        listOf("bad tool!", "x".repeat(81), "").forEachIndexed { index, unsafeName ->
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.ToolActivity(null)),
                pipeline.process(
                    ParsedSseFrame(
                        "tool.completed",
                        baseFrame(4L + index).put("tool_name", unsafeName).toString()
                    )
                )
            )
        }
    }

    @Test
    fun reasoningAndCommentaryTextNeverCrossesTheBoundary() {
        val pipeline = startedPipeline()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.Reasoning(active = true)),
            pipeline.process(
                ParsedSseFrame(
                    "tool.progress",
                    baseFrame(3).put("tool_name", "_thinking").put("delta", "SECRET-THOUGHT").toString()
                )
            )
        )
        val commentary = pipeline.process(
            ParsedSseFrame(
                "assistant.commentary",
                baseFrame(4).put("text", "SECRET-COMMENTARY").toString()
            )
        )
        val reasoningDelta = pipeline.process(
            ParsedSseFrame(
                "reasoning.delta",
                baseFrame(5).put("text", "SECRET-REASONING").toString()
            )
        )

        assertTrue(commentary.isEmpty())
        assertTrue(reasoningDelta.isEmpty())
        assertFalse(commentary.toString().contains("SECRET"))
        assertFalse(reasoningDelta.toString().contains("SECRET"))
    }

    @Test
    fun unicodeAssistantTextIsPreservedExactly() {
        val text = "Hä 😀 中文 مرحبا e\u0301 \u200B"
        val pipeline = startedPipeline()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta(text)),
            pipeline.process(deltaFrame(3, MSG_ID, text))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted(text)),
            pipeline.process(completedFrame(4, MSG_ID, text))
        )
    }

    @Test
    fun runFailedIsTerminalWithoutAssistantCompletedAndStillNeedsDone() {
        val pipeline = ChatStreamEventPipeline(SESSION_ID, requireDoneAfterTerminal = true)
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.ServerError)),
            pipeline.process(
                frame("run.failed", 3, JSONObject().put("error", "SECRET-SERVER-DETAIL"))
            )
        )
        assertTrue(pipeline.process(frame("done", 4)).isEmpty())
        pipeline.endOfStream()
        assertProtocolViolation(pipeline, frame("run.failed", 5))
    }

    @Test
    fun unknownFieldsOnKnownEventsAreNeverPropagated() {
        val pipeline = ChatStreamEventPipeline(SESSION_ID)
        val events = pipeline.process(
            ParsedSseFrame(
                "run.started",
                baseFrame(1)
                    .put("user_message", "SECRET-USER")
                    .put("usage", JSONObject().put("tokens", "SECRET-USAGE"))
                    .put("runtime", 1.5)
                    .toString()
            )
        )

        assertEquals(listOf<ChatTurnEvent>(ChatTurnEvent.RunStarted(RUN_ID)), events)
        assertFalse(events.toString().contains("SECRET"))
    }

    private fun startedPipeline(): ChatStreamEventPipeline {
        val pipeline = ChatStreamEventPipeline(SESSION_ID)
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        return pipeline
    }

    private fun completedPipeline(): ChatStreamEventPipeline {
        val pipeline = startedPipeline()
        pipeline.process(completedFrame(3, MSG_ID, "Hallo"))
        return pipeline
    }

    private fun approvalPipeline(): ChatStreamEventPipeline {
        val pipeline = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        pipeline.process(frame("run.started", 1, runId = APPROVAL_RUN_ID))
        pipeline.process(messageStartedFrame(2, MSG_ID, APPROVAL_RUN_ID))
        return pipeline
    }

    private fun approvalPayload(seq: Long): JSONObject = baseFrame(seq, APPROVAL_RUN_ID)
        .put("request_id", REQUEST_ID)
        .put("description", ApprovalRequest.SAFE_DESCRIPTION)
        .put("choices", JSONArray().put("once").put("deny"))

    private fun resumedWithComposed(length: Int): ChatStreamEventPipeline =
        ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            resume = ChatStreamResumeState(
                runId = RUN_ID,
                lastSeq = 2L,
                messageStarted = true,
                messageId = MSG_ID,
                composed = "x".repeat(length),
                assistantCompleted = false,
                finalText = null,
                runCompleted = false,
                sawError = false
            )
        )

    private fun frame(
        name: String,
        seq: Long,
        extra: JSONObject = JSONObject(),
        runId: String = RUN_ID
    ): ParsedSseFrame {
        val payload = baseFrame(seq, runId)
        for (key in extra.keys()) {
            payload.put(key, extra.get(key))
        }
        return ParsedSseFrame(name, payload.toString())
    }

    private fun baseFrame(seq: Long, runId: String = RUN_ID): JSONObject = JSONObject()
        .put("session_id", SESSION_ID)
        .put("run_id", runId)
        .put("seq", seq)
        .put("ts", 1000.25)

    private fun messageStartedFrame(
        seq: Long,
        messageId: String,
        runId: String = RUN_ID
    ): ParsedSseFrame = ParsedSseFrame(
        "message.started",
        baseFrame(seq, runId)
            .put("message", JSONObject().put("id", messageId).put("role", "assistant"))
            .toString()
    )

    private fun deltaFrame(seq: Long, messageId: String, delta: String): ParsedSseFrame =
        ParsedSseFrame(
            "assistant.delta",
            baseFrame(seq)
                .put("message_id", messageId)
                .put("delta", delta)
                .toString()
        )

    private fun completedFrame(
        seq: Long,
        messageId: String,
        content: String,
        runId: String = RUN_ID
    ): ParsedSseFrame =
        ParsedSseFrame(
            "assistant.completed",
            baseFrame(seq, runId)
                .put("message_id", messageId)
                .put("content", content)
                .toString()
        )

    private fun assertProtocolViolation(
        pipeline: ChatStreamEventPipeline,
        frame: ParsedSseFrame,
        label: String = "expected protocol violation"
    ) {
        try {
            pipeline.process(frame)
            fail(label)
        } catch (expected: ChatTurnProtocolException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    private companion object {
        const val SESSION_ID = "synthetic-session"
        const val RUN_ID = "run_synthetic"
        const val APPROVAL_RUN_ID = "run_0123456789abcdef0123456789abcdef"
        const val REQUEST_ID = "fedcba9876543210fedcba9876543210"
        const val MSG_ID = "msg_synthetic"
        const val MAX_COMPOSED = 1024 * 1024
    }
}
