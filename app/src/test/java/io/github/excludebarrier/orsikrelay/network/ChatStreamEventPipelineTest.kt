package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatStreamEventPipelineTest {

    private val pipeline = ChatStreamEventPipeline(SESSION_ID)

    @Test
    fun fullSuccessPathWithDeltasAndFinalContent() {
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.RunStarted(RUN_ID)),
            pipeline.process(frame("run.started", 1))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantStarted),
            pipeline.process(messageStartedFrame(2, MSG_ID))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("Hallo")),
            pipeline.process(deltaFrame(3, MSG_ID, "Hallo"))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta(" Welt")),
            pipeline.process(deltaFrame(4, MSG_ID, " Welt"))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("Hallo Welt")),
            pipeline.process(completedFrame(5, MSG_ID, "Hallo Welt"))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted()),
            pipeline.process(
                frame(
                    "run.completed",
                    6,
                    JSONObject()
                        .put("messages", org.json.JSONArray())
                        .put("usage", JSONObject().put("hidden", "hidden-usage"))
                        .put("runtime", 12.5)
                )
            )
        )
        assertTrue(pipeline.process(frame("done", 7)).isEmpty())
    }

    @Test
    fun runCompletedPreservesNonBlankPendingSteerExactly() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(completedFrame(3, MSG_ID, "Hallo Welt"))

        // Mission 008B: a non-blank `pending_steer` string is the only
        // `run.completed` field that crosses the pipeline boundary, and it
        // is preserved exactly (no trimming, no escaping, no limit).
        val raw = "  führe  um \n\tweiter  "
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted(raw)),
            pipeline.process(
                frame(
                    "run.completed",
                    4,
                    JSONObject()
                        .put("messages", org.json.JSONArray())
                        .put("usage", JSONObject().put("hidden", "hidden-usage"))
                        .put("pending_steer", raw)
                )
            )
        )
        assertTrue(pipeline.process(frame("done", 5)).isEmpty())
    }

    @Test
    fun runCompletedPendingSteerEdgeCasesYieldNull() {
        // Absent, null, non-string and blank `pending_steer` all map to a
        // plain terminal success (no pending steer carried).
        listOf<JSONObject>(
            JSONObject(), // absent
            JSONObject().put("pending_steer", org.json.JSONObject.NULL), // null
            JSONObject().put("pending_steer", 42), // non-string
            JSONObject().put("pending_steer", "   "), // blank
            JSONObject().put("pending_steer", "\n\t") // blank (whitespace)
        ).forEachIndexed { index, extra ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            fresh.process(frame("run.started", 1))
            fresh.process(messageStartedFrame(2, MSG_ID))
            fresh.process(completedFrame(3, MSG_ID, "Hallo"))
            assertEquals(
                "edge case $index must not carry a pending steer",
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted()),
                fresh.process(frame("run.completed", 4, extra))
            )
        }
    }

    @Test
    fun emptyAssistantDeltaIsAValidNoOpFragment() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("")),
            pipeline.process(deltaFrame(3, MSG_ID, ""))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("")),
            pipeline.process(completedFrame(4, MSG_ID, ""))
        )
    }

    @Test
    fun completionWithoutDeltasUsesEmptyComposedText() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("")),
            pipeline.process(baseFrame(3).put("message_id", MSG_ID).toString().let {
                ParsedSseFrame("assistant.completed", it)
            })
        )
    }

    @Test
    fun completionWithoutContentFallsBackToComposedDeltas() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(deltaFrame(3, MSG_ID, "Hal"))
        pipeline.process(deltaFrame(4, MSG_ID, "lo"))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("Hallo")),
            pipeline.process(
                ParsedSseFrame(
                    "assistant.completed",
                    baseFrame(5).put("message_id", MSG_ID).toString()
                )
            )
        )
    }

    @Test
    fun unicodeAndInnerLineBreaksInDeltasArePreserved() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("Hä\ncö")),
            pipeline.process(deltaFrame(3, MSG_ID, "Hä\ncö"))
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantDelta("😀")),
            pipeline.process(deltaFrame(4, MSG_ID, "😀"))
        )
    }

    @Test
    fun toolEventsMapToGenericActivityWithoutPayloadDetails() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        listOf("tool.started", "tool.completed", "tool.failed").forEachIndexed { index, name ->
            val payload = baseFrame(3 + index.toLong())
                .put("name", "hidden-tool")
                .put("args", JSONObject().put("preview", "hidden-preview"))
            val events = pipeline.process(ParsedSseFrame(name, payload.toString()))
            assertEquals(listOf<ChatTurnEvent>(ChatTurnEvent.ToolActivity()), events)
            assertFalse(events.toString().contains("hidden-tool"))
            assertFalse(events.toString().contains("hidden-preview"))
        }
    }

    @Test
    fun toolProgressAndReasoningEventsAreSilentlyIgnored() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        listOf("tool.progress", "reasoning.started", "reasoning.delta", "reasoning.completed")
            .forEachIndexed { index, name ->
                val events = pipeline.process(frame(name, 3 + index.toLong()))
                assertTrue(events.isEmpty())
            }
    }

    @Test
    fun errorEventProducesOnlyClosedGenericFailure() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))

        val events = pipeline.process(
            ParsedSseFrame(
                "error",
                baseFrame(3).put("message", "dynamic server detail").toString()
            )
        )
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Http)),
            events
        )
        assertFalse(events.toString().contains("dynamic server detail"))

        // A trailing `done` after `error` is accepted and produces nothing.
        assertTrue(pipeline.process(frame("done", 4)).isEmpty())
    }

    @Test
    fun duplicateRunIdSeqPairIsDroppedNotAppliedTwice() {
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.RunStarted(RUN_ID)),
            pipeline.process(frame("run.started", 1))
        )
        assertTrue(pipeline.process(frame("run.started", 1)).isEmpty())

        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(deltaFrame(3, MSG_ID, "Hallo"))
        assertTrue(pipeline.process(deltaFrame(3, MSG_ID, "Hallo")).isEmpty())
    }

    @Test
    fun sameSeqWithDifferentEventIsRejectedClosed() {
        pipeline.process(frame("run.started", 1))
        assertProtocolViolation(pipeline, frame("assistant.delta", 1, deltaExtra(MSG_ID, "x")))
    }

    @Test
    fun sameSeqAndEventWithDifferentPayloadIsRejectedNotDeduplicated() {
        pipeline.process(frame("run.started", 1))
        val contradictory = ParsedSseFrame(
            "run.started",
            baseFrame(1).put("ts", 2000.0).toString()
        )
        assertProtocolViolation(
            pipeline,
            contradictory,
            "same seq+event with different payload must be contradictory"
        )
    }

    @Test
    fun sequenceMustStartAtExactlyOne() {
        val fresh = ChatStreamEventPipeline(SESSION_ID)
        // A first event with `seq` > 1 starts the sequence too late.
        assertProtocolViolation(fresh, frame("run.started", 2))
        // `seq = 0` is not positive at all.
        assertProtocolViolation(ChatStreamEventPipeline(SESSION_ID), frame("run.started", 0))
        // `seq = 1` is the only valid start.
        val valid = ChatStreamEventPipeline(SESSION_ID)
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.RunStarted(RUN_ID)),
            valid.process(frame("run.started", 1))
        )
    }

    @Test
    fun sequenceMustAdvanceByExactlyOne() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        // A gap: after 2, only 3 is allowed.
        assertProtocolViolation(pipeline, deltaFrame(4, MSG_ID, "a"))
    }

    @Test
    fun backwardSequenceIsRejectedClosed() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(deltaFrame(3, MSG_ID, "a"))
        // Backward `seq` with a different payload is contradictory.
        assertProtocolViolation(pipeline, deltaFrame(2, MSG_ID, "b"))
        // The last-seen frame resubmitted identically is dropped, not
        // applied twice, even though its `seq` equals `lastSeq`.
        assertTrue(pipeline.process(deltaFrame(3, MSG_ID, "a")).isEmpty())
    }

    @Test
    fun doneWithoutRunCompletedOrErrorIsIncompatible() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(pipeline, frame("done", 3))
    }

    @Test
    fun unknownEventIsRejectedClosed() {
        pipeline.process(frame("run.started", 1))
        assertProtocolViolation(pipeline, frame("mystery.event", 2))
    }

    @Test
    fun interactiveApprovalRequestMapsOnlyProtocolDefinedFields() {
        val approvalPipeline = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        approvalPipeline.process(frame("run.started", 1))
        approvalPipeline.process(messageStartedFrame(2, MSG_ID))
        val events = approvalPipeline.process(
            ParsedSseFrame(
                "approval.request",
                baseFrame(3)
                    .put("description", "A dangerous action requires your approval.")
                    .put("request_id", "0123456789abcdef0123456789abcdef")
                    .put("choices", org.json.JSONArray().put("once").put("deny"))
                    .toString()
            )
        )

        assertEquals(1, events.size)
        val event = events.single() as ChatTurnEvent.ApprovalRequested
        assertEquals(RUN_ID, event.request.runId)
        assertEquals("0123456789abcdef0123456789abcdef", event.request.requestId)
        assertEquals(
            "A dangerous action requires your approval.",
            event.request.description
        )
    }

    @Test
    fun approvalRequestWithoutNegotiatedCapabilityIsRejectedClosed() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(
            pipeline,
            ParsedSseFrame(
                "approval.request",
                baseFrame(3)
                    .put("description", "A dangerous action requires your approval.")
                    .put("request_id", "0123456789abcdef0123456789abcdef")
                    .put("choices", org.json.JSONArray().put("once").put("deny"))
                    .toString()
            )
        )
    }

    @Test
    fun approvalRequestWithDynamicOrPermanentFieldsIsRejectedClosed() {
        listOf(
            "command" to "synthetic-command",
            "pattern_key" to "synthetic-pattern",
            "output" to "synthetic-output",
            "always" to true
        ).forEach { (field, value) ->
            val approvalPipeline = ChatStreamEventPipeline(
                expectedSessionId = SESSION_ID,
                interactiveApprovals = true
            )
            approvalPipeline.process(frame("run.started", 1))
            approvalPipeline.process(messageStartedFrame(2, MSG_ID))
            assertProtocolViolation(
                approvalPipeline,
                ParsedSseFrame(
                    "approval.request",
                    baseFrame(3)
                        .put("description", "A dangerous action requires your approval.")
                        .put("request_id", "0123456789abcdef0123456789abcdef")
                        .put("choices", org.json.JSONArray().put("once").put("deny"))
                        .put(field, value)
                        .toString()
                )
            )
        }
    }

    @Test
    fun malformedInteractiveApprovalRequestIsRejectedClosed() {
        val approvalPipeline = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        approvalPipeline.process(frame("run.started", 1))
        approvalPipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(
            approvalPipeline,
            ParsedSseFrame(
                "approval.request",
                baseFrame(3)
                    .put("description", "dynamic server text")
                    .put("choices", org.json.JSONArray().put("once").put("always"))
                    .toString()
            )
        )
    }

    @Test
    fun approvalIdentifiersRejectPathsUrlsKeysAndWrongUuidShapes() {
        val invalidRequestIds = listOf(
            "/etc/passwd",
            "https://example.invalid/path",
            "Bearer-synthetic-key",
            "A".repeat(32),
            "a".repeat(31)
        )
        invalidRequestIds.forEach { invalidId ->
            val target = approvalPipeline(RUN_ID)
            assertProtocolViolation(target, approvalFrame(3, RUN_ID, invalidId))
        }

        val invalidRunIds = listOf(
            "/tmp/run",
            "https://example.invalid/run",
            "Bearer-synthetic-key",
            "run_" + "A".repeat(32),
            "run_" + "a".repeat(31)
        )
        invalidRunIds.forEach { invalidId ->
            val target = approvalPipeline(invalidId)
            assertProtocolViolation(target, approvalFrame(3, invalidId, REQUEST_ID))
        }
    }

    @Test
    fun approvalRequestWithServerPreFixFormatIsRejectedClosed() {
        // Regression: the server previously emitted the approval.request event
        // with a `command` field and a dynamic description. This exact shape
        // must be rejected closed (the `command` field is not in the closed
        // allowlist, and the dynamic description is not the fixed neutral text).
        val target = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        target.process(frame("run.started", 1))
        target.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(
            target,
            ParsedSseFrame(
                "approval.request",
                baseFrame(3)
                    .put("description", "delete in root path")
                    .put("request_id", REQUEST_ID)
                    .put("choices", org.json.JSONArray().put("once").put("deny"))
                    .put("command", "[redacted]")
                    .toString()
            ),
            "pre-fix server format (command + dynamic description) must be rejected"
        )
    }

    @Test
    fun approvalRequestWithSessionChoiceIsRejectedClosed() {
        // The mobile protocol v1 offers only `once` and `deny`. A `session`
        // choice is not part of the closed choice set and must be rejected.
        val target = approvalPipeline(RUN_ID)
        assertProtocolViolation(
            target,
            ParsedSseFrame(
                "approval.request",
                baseFrame(3)
                    .put("description", "A dangerous action requires your approval.")
                    .put("request_id", REQUEST_ID)
                    .put("choices", org.json.JSONArray().put("once").put("session"))
                    .toString()
            ),
            "session choice must be rejected"
        )
    }

    @Test
    fun messageStartedBeforeRunStartedIsRejected() {
        assertProtocolViolation(pipeline, messageStartedFrame(1, MSG_ID))
    }

    @Test
    fun duplicateRunStartedIsRejectedAfterFirst() {
        pipeline.process(frame("run.started", 1))
        assertProtocolViolation(pipeline, frame("run.started", 2))
    }

    @Test
    fun secondMessageStartedIsRejected() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(pipeline, messageStartedFrame(3, MSG_ID))
    }

    @Test
    fun messageStartedRequiresAssistantRoleAndMessageId() {
        feedRunStarted()
        assertProtocolViolation(
            pipeline,
            ParsedSseFrame(
                "message.started",
                baseFrame(2)
                    .put("message", JSONObject().put("id", MSG_ID).put("role", "user"))
                    .toString()
            )
        )
        assertProtocolViolation(
            pipeline,
            ParsedSseFrame(
                "message.started",
                baseFrame(3).put("message", JSONObject().put("role", "assistant")).toString()
            )
        )
        assertProtocolViolation(
            pipeline,
            ParsedSseFrame("message.started", baseFrame(4).toString())
        )
    }

    @Test
    fun deltaRequiresStartedMatchingMessage() {
        feedRunStarted()
        assertProtocolViolation(pipeline, deltaFrame(2, MSG_ID, "early"))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(pipeline, deltaFrame(3, "other-message", "x"))
        assertProtocolViolation(pipeline, deltaFrame(4, MSG_ID, ""))
    }

    @Test
    fun deltaAfterCompletionIsRejected() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(deltaFrame(3, MSG_ID, "Hallo"))
        pipeline.process(completedFrame(4, MSG_ID, "Hallo"))
        assertProtocolViolation(pipeline, deltaFrame(5, MSG_ID, "mehr"))
    }

    @Test
    fun secondAssistantCompletedIsRejected() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(completedFrame(3, MSG_ID, "Hallo"))
        assertProtocolViolation(pipeline, completedFrame(4, MSG_ID, "Hallo"))
    }

    @Test
    fun runCompletedHappensExactlyOnceAfterAssistantCompleted() {
        pipeline.process(frame("run.started", 1))
        assertProtocolViolation(pipeline, frame("run.completed", 2))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        // Tightened success order: `run.completed` requires
        // `assistant.completed`, not just `message.started`.
        assertProtocolViolation(pipeline, frame("run.completed", 3))
        pipeline.process(completedFrame(3, MSG_ID, "Hallo"))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted()),
            pipeline.process(frame("run.completed", 4))
        )
        assertProtocolViolation(pipeline, frame("run.completed", 5))
    }

    @Test
    fun runCompletedAfterErrorIsRejected() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        pipeline.process(
            ParsedSseFrame("error", baseFrame(3).put("message", "x").toString())
        )
        assertProtocolViolation(pipeline, frame("run.completed", 4))
    }

    @Test
    fun sessionMismatchIsRejected() {
        pipeline.process(frame("run.started", 1))
        val other = ParsedSseFrame(
            "run.started",
            baseFrame(2).put("session_id", "other-session").toString()
        )
        assertProtocolViolation(pipeline, other)
    }

    @Test
    fun differentRunIdIsRejected() {
        pipeline.process(frame("run.started", 1))
        val other = ParsedSseFrame(
            "message.started",
            baseFrame(2)
                .put("run_id", "other-run")
                .put("message", JSONObject().put("id", MSG_ID).put("role", "assistant"))
                .toString()
        )
        assertProtocolViolation(pipeline, other)
    }

    @Test
    fun blankOversizedOrControlCharacterIdsAreRejected() {
        listOf("", "x".repeat(257), "a\nb").forEach { badId ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            val frame = ParsedSseFrame(
                "run.started",
                baseFrame(1).put("run_id", badId).toString()
            )
            assertProtocolViolation(fresh, frame, "run_id=$badId")
        }
    }

    @Test
    fun seqMustBePositiveExactInteger() {
        listOf(0L, -1L, 1.5, "2", JSONObject.NULL).forEachIndexed { index, badSeq ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            val frame = ParsedSseFrame("run.started", baseFrame(1).put("seq", badSeq).toString())
            assertProtocolViolation(fresh, frame, "seq fixture $index")
        }
        val missing = ChatStreamEventPipeline(SESSION_ID)
        val noSeq = ParsedSseFrame(
            "run.started",
            baseFrame(1).let { json -> json.remove("seq"); json.toString() }
        )
        assertProtocolViolation(missing, noSeq)
    }

    @Test
    fun seqBeyondLongRangeIsRejectedClosed() {
        // A `seq` that does not fit in a signed 64-bit integer is
        // rejected closed, no matter how it is spelled.
        listOf("9223372036854775808", "1e19", Long.MAX_VALUE.toString() + "1").forEach { raw ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            val frame = ParsedSseFrame(
                "run.started",
                baseFrame(1).put("seq", raw).toString()
            )
            assertProtocolViolation(fresh, frame, "seq overflow fixture $raw")
        }
        // A valid frame shape whose only defect is an overflowing `seq`
        // (spelled as a raw JSON number) is rejected for the sequence,
        // not for any other field.
        val overflow = ChatStreamEventPipeline(SESSION_ID)
        val rawOverflow = ParsedSseFrame(
            "run.started",
            """{"session_id":"synthetic-session","run_id":"run_synthetic","seq":9223372036854775808,"ts":1000.25}"""
        )
        assertProtocolViolation(overflow, rawOverflow, "raw JSON seq overflow")
    }

    @Test
    fun timestampMustBeFiniteNonNegativeNumber() {
        listOf(-1.0, "1.5", "NaN", "Infinity", JSONObject.NULL).forEachIndexed { index, badTs ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            val frame = ParsedSseFrame("run.started", baseFrame(1).put("ts", badTs).toString())
            assertProtocolViolation(fresh, frame, "ts fixture $index")
        }
        val missing = ChatStreamEventPipeline(SESSION_ID)
        val noTs = ParsedSseFrame(
            "run.started",
            baseFrame(1).let { json -> json.remove("ts"); json.toString() }
        )
        assertProtocolViolation(missing, noTs)
        // Fractions are allowed.
        val fractional = ChatStreamEventPipeline(SESSION_ID)
        fractional.process(ParsedSseFrame("run.started", baseFrame(1).put("ts", 0.25).toString()))
    }

    @Test
    fun sessionIdMustBePresentAndString() {
        listOf(JSONObject.NULL, 42).forEachIndexed { index, bad ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            val frame = ParsedSseFrame("run.started", baseFrame(1).put("session_id", bad).toString())
            assertProtocolViolation(fresh, frame, "session fixture $index")
        }
    }

    @Test
    fun frameWithoutNameOrWithoutDataIsRejected() {
        assertProtocolViolation(pipeline, ParsedSseFrame(null, baseFrame(1).toString()))
        assertProtocolViolation(pipeline, ParsedSseFrame("run.started", null))
        assertProtocolViolation(pipeline, ParsedSseFrame("run.started", "not-json"))
    }

    @Test
    fun oversizedFinalContentIsRejected() {
        feedRunStarted()
        pipeline.process(messageStartedFrame(2, MSG_ID))
        assertProtocolViolation(
            pipeline,
            completedFrame(3, MSG_ID, "x".repeat(1024 * 1024 + 1))
        )
    }

    @Test
    fun runCompletedRequiresAssistantCompleted() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))
        // Without `assistant.completed` the success order is incomplete.
        assertProtocolViolation(pipeline, frame("run.completed", 3))
        // With `assistant.completed` the same frame is accepted.
        pipeline.process(completedFrame(3, MSG_ID, "Hallo"))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnCompleted()),
            pipeline.process(frame("run.completed", 4))
        )
    }

    @Test
    fun toolAndReasoningBeforeRunStartedAreRejected() {
        listOf("tool.started", "reasoning.started").forEachIndexed { index, name ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            assertProtocolViolation(fresh, ParsedSseFrame(name, baseFrame(1).toString()), name)
            // With `run.started` but before `message.started` they are still
            // rejected.
            fresh.process(frame("run.started", 1))
            assertProtocolViolation(fresh, ParsedSseFrame(name, baseFrame(2 + index.toLong()).toString()), name)
        }
    }

    @Test
    fun toolAndReasoningAfterAssistantCompletedAreRejected() {
        listOf("tool.started", "tool.progress", "reasoning.started").forEach { name ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            fresh.process(frame("run.started", 1))
            fresh.process(messageStartedFrame(2, MSG_ID))
            fresh.process(completedFrame(3, MSG_ID, "Hallo"))
            assertProtocolViolation(fresh, ParsedSseFrame(name, baseFrame(4).toString()), name)
        }
    }

    @Test
    fun toolAndReasoningAfterRunCompletedAreRejected() {
        listOf("tool.started", "tool.progress", "reasoning.completed").forEach { name ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            fresh.process(frame("run.started", 1))
            fresh.process(messageStartedFrame(2, MSG_ID))
            fresh.process(completedFrame(3, MSG_ID, "Hallo"))
            fresh.process(frame("run.completed", 4))
            assertProtocolViolation(fresh, ParsedSseFrame(name, baseFrame(5).toString()), name)
        }
    }

    @Test
    fun toolAndReasoningAfterErrorAreRejected() {
        listOf("tool.failed", "reasoning.delta").forEach { name ->
            val fresh = ChatStreamEventPipeline(SESSION_ID)
            fresh.process(frame("run.started", 1))
            fresh.process(messageStartedFrame(2, MSG_ID))
            fresh.process(ParsedSseFrame("error", baseFrame(3).put("message", "x").toString()))
            assertProtocolViolation(fresh, ParsedSseFrame(name, baseFrame(4).toString()), name)
        }
    }

    @Test
    fun toolAndReasoningBetweenMessageStartedAndAssistantCompletedAreStillAllowed() {
        pipeline.process(frame("run.started", 1))
        pipeline.process(messageStartedFrame(2, MSG_ID))

        val tool = ParsedSseFrame(
            "tool.started",
            baseFrame(3)
                .put("name", "hidden-tool")
                .put("args", JSONObject().put("preview", "hidden-preview"))
                .toString()
        )
        assertEquals(listOf<ChatTurnEvent>(ChatTurnEvent.ToolActivity()), pipeline.process(tool))

        listOf("tool.progress", "reasoning.started", "reasoning.delta", "reasoning.completed")
            .forEachIndexed { index, name ->
                val events = pipeline.process(frame(name, 4 + index.toLong()))
                assertTrue(events.isEmpty())
            }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.AssistantCompleted("Hallo Welt")),
            pipeline.process(completedFrame(8, MSG_ID, "Hallo Welt"))
        )
    }

    private fun approvalPipeline(runId: String): ChatStreamEventPipeline {
        val target = ChatStreamEventPipeline(
            expectedSessionId = SESSION_ID,
            interactiveApprovals = true
        )
        target.process(frame("run.started", 1, JSONObject().put("run_id", runId)))
        target.process(messageStartedFrame(2, MSG_ID, runId))
        return target
    }

    private fun approvalFrame(
        seq: Long,
        runId: String,
        requestId: String
    ): ParsedSseFrame = ParsedSseFrame(
        "approval.request",
        baseFrame(seq, runId)
            .put("description", "A dangerous action requires your approval.")
            .put("request_id", requestId)
            .put("choices", org.json.JSONArray().put("once").put("deny"))
            .toString()
    )

    private fun feedRunStarted() {
        pipeline.process(frame("run.started", 1))
    }

    private fun frame(name: String, seq: Long, extra: JSONObject = JSONObject()): ParsedSseFrame {
        val payload = baseFrame(seq)
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

    private fun deltaExtra(messageId: String, delta: String): JSONObject = JSONObject()
        .put("message_id", messageId)
        .put("delta", delta)

    private fun deltaFrame(seq: Long, messageId: String, delta: String): ParsedSseFrame =
        frame("assistant.delta", seq, deltaExtra(messageId, delta))

    private fun messageStartedFrame(
        seq: Long,
        messageId: String,
        runId: String = RUN_ID
    ): ParsedSseFrame =
        ParsedSseFrame(
            "message.started",
            baseFrame(seq, runId)
                .put("message", JSONObject().put("id", messageId).put("role", "assistant"))
                .toString()
        )

    private fun completedFrame(seq: Long, messageId: String, content: String): ParsedSseFrame =
        ParsedSseFrame(
            "assistant.completed",
            baseFrame(seq)
                .put("message_id", messageId)
                .put("content", content)
                .put("completed", true)
                .put("partial", false)
                .put("interrupted", false)
                .toString()
        )

    private fun assertProtocolViolation(
        target: ChatStreamEventPipeline,
        frame: ParsedSseFrame,
        label: String = "expected protocol violation"
    ) {
        try {
            target.process(frame)
            fail("$label")
        } catch (expected: ChatTurnProtocolException) {
            assertTrue(expected.message.isNullOrBlank())
        }
    }

    private companion object {
        const val SESSION_ID = "synthetic-session"
        const val RUN_ID = "run_0123456789abcdef0123456789abcdef"
        const val REQUEST_ID = "fedcba9876543210fedcba9876543210"
        const val MSG_ID = "msg_synthetic"
    }
}
