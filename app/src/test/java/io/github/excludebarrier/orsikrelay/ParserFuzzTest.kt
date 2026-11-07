package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.SlashInput
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairCode
import io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.navigation.decodeRouteArgument
import io.github.excludebarrier.orsikrelay.network.ChatStreamEventPipeline
import io.github.excludebarrier.orsikrelay.network.ChatTurnProtocolException
import io.github.excludebarrier.orsikrelay.network.ParsedSseFrame
import io.github.excludebarrier.orsikrelay.network.SseFrameParser
import io.github.excludebarrier.orsikrelay.network.SseFrameParserException
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.LinkSchemePolicy
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.linkDomain
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.looksLikeOutput
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.terminalCommandFrom
import kotlin.random.Random
import org.junit.Assert.fail
import org.junit.Test

/**
 * Random input against the parsers that read server or user text. Each
 * may only answer with its own closed failure, never with any other
 * exception (a crash on hostile input). Fixed seeds: reproducible.
 */
class ParserFuzzTest {

    private val alphabet = "aZ09 :/?#&=%.-_@[]{}\"'\\\n\r\t\u0000\u007f﻿äß€😀\uD800" +
        "https://orsik pair server code run_id seq data event id retry"

    private fun Random.text(max: Int): String {
        val length = nextInt(max + 1)
        return buildString(length) {
            repeat(length) {
                append(if (nextInt(8) == 0) nextInt(0, 0x10000).toChar() else alphabet[nextInt(alphabet.length)])
            }
        }
    }

    private fun Random.jsonValue(depth: Int = 0): String = when (if (depth > 2) nextInt(5) else nextInt(7)) {
        0 -> "null"
        1 -> nextLong().toString()
        2 -> if (nextBoolean()) "true" else "false"
        3 -> "\"" + text(12).replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' } + "\""
        4 -> (nextDouble() * 1e6).toString()
        5 -> "[" + (0 until nextInt(3)).joinToString(",") { jsonValue(depth + 1) } + "]"
        else -> jsonObject(depth + 1)
    }

    private val keys = listOf(
        "assistant", "choices", "content", "delta", "description", "error", "id", "message",
        "message_id", "pending_steer", "request_id", "role", "run_id", "seq", "session_id",
        "stopped", "_thinking", "tool_name", "ts", "status", "x"
    )

    private fun Random.jsonObject(depth: Int = 0): String =
        "{" + keys.shuffled(this).take(nextInt(6)).joinToString(",") { "\"$it\":${jsonValue(depth)}" } + "}"

    private val events = listOf(
        "approval.request", "assistant.commentary", "assistant.completed", "assistant.delta", "done",
        "error", "message.started", "reasoning.completed", "reasoning.delta", "reasoning.started",
        "run.cancelled", "run.completed", "run.failed", "run.started", "tool.completed",
        "tool.failed", "tool.progress", "tool.started", "unknown"
    )

    private fun noCrash(what: String, input: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            fail("$what crashed with ${error::class.java.name} on ${input.take(200).map { "%04x".format(it.code) }}")
        }
    }

    @Test
    fun sseFramesFailOnlyClosed() {
        val random = Random(1)
        repeat(20_000) {
            val parser = SseFrameParser(maxLineBytes = 4096, maxFrameBytes = 4096)
            val chunks = List(random.nextInt(1, 6)) {
                if (random.nextBoolean()) random.text(200)
                else "event: ${events[random.nextInt(events.size)]}\ndata: ${random.jsonObject()}\n\n"
            }
            noCrash("SseFrameParser", chunks.joinToString("|")) {
                try {
                    chunks.forEach { parser.feed(it) }
                } catch (_: SseFrameParserException) {
                }
            }
        }
    }

    @Test
    fun chatStreamEventsFailOnlyClosed() {
        val random = Random(2)
        repeat(200_000) {
            val pipeline = ChatStreamEventPipeline(
                expectedSessionId = "s1",
                requireDoneAfterTerminal = random.nextBoolean(),
                interactiveApprovals = random.nextBoolean()
            )
            // A valid run.started first, then mostly well-formed frames with
            // random extra fields, so the fuzz reaches the per-event code.
            var seq = 1L
            val frames = List(random.nextInt(1, 12)) { index ->
                val name = if (index == 0 && random.nextInt(4) > 0) "run.started" else events[random.nextInt(events.size)]
                val data = when (random.nextInt(10)) {
                    0 -> random.text(40)
                    1 -> null
                    else -> {
                        val payload = org.json.JSONObject()
                            .put("session_id", if (random.nextInt(20) == 0) "other" else "s1")
                            .put("run_id", if (random.nextInt(20) == 0) "r2" else "r1")
                            .put("seq", if (random.nextInt(15) == 0) random.nextLong(-2, 50) else seq++)
                            .put("ts", 1000.5)
                        repeat(random.nextInt(5)) {
                            val key = keys[random.nextInt(keys.size)]
                            val raw = random.jsonValue()
                            payload.put(key, org.json.JSONTokener(raw).nextValue())
                        }
                        payload.toString()
                    }
                }
                ParsedSseFrame(name.takeIf { random.nextInt(30) > 0 }, data)
            }
            noCrash("ChatStreamEventPipeline", frames.toString()) {
                try {
                    frames.forEach { pipeline.process(it) }
                    pipeline.endOfStream()
                } catch (_: ChatTurnProtocolException) {
                }
            }
        }
    }

    @Test
    fun textParsersNeverThrow() {
        val random = Random(3)
        val prefixes = listOf("", "orsik://pair?", "https://", "/", "@", "```bash\n", "https://a.b/")
        repeat(50_000) {
            val input = prefixes[random.nextInt(prefixes.size)] + random.text(80)
            noCrash("PairingDeepLink", input) { PairingDeepLink.parse(input) }
            noCrash("ProfileBaseUrlValidator", input) { ProfileBaseUrlValidator.validate(input) }
            noCrash("DevicePairCode", input) { DevicePairCode.normalize(input) }
            noCrash("SlashInput", input) { SlashInput.parse(input) }
            noCrash("ChatMessageValidator", input) { ChatMessageValidator.validate(input) }
            noCrash("LinkSchemePolicy", input) { LinkSchemePolicy.isAllowed(input) }
            noCrash("linkDomain", input) { linkDomain(input) }
            noCrash("terminalCommandFrom", input) { terminalCommandFrom(input) }
            noCrash("looksLikeOutput", input) { looksLikeOutput(input) }
            noCrash("decodeRouteArgument", input) { decodeRouteArgument(input) }
        }
    }
}
