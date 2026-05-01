package com.aibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamChunkContractTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `content chunk matches openai-like shape`() {
        val chunk = ChatCompletionChunkResponse(
            id = "chatcmpl-test",
            created = 123L,
            model = "gpt-4o",
            choices = listOf(
                ChatChunkChoice(
                    index = 0,
                    delta = ChatChunkDelta(content = "hello"),
                    finish_reason = null
                )
            )
        )

        val json = mapper.readTree(mapper.writeValueAsString(chunk))
        assertEquals("chat.completion.chunk", json["object"].asText())
        assertEquals("hello", json["choices"][0]["delta"]["content"].asText())
        assertTrue(json["choices"][0].has("finish_reason"))
        assertTrue(json["choices"][0]["finish_reason"].isNull)
    }

    @Test
    fun `tool call chunk includes function payload`() {
        val chunk = ChatCompletionChunkResponse(
            id = "chatcmpl-test",
            created = 123L,
            model = "gpt-4o",
            choices = listOf(
                ChatChunkChoice(
                    index = 0,
                    delta = ChatChunkDelta(
                        tool_calls = listOf(
                            ChatChunkToolCall(
                                index = 0,
                                id = "call_1",
                                function = FunctionCall("read_file", """{"path":"README.md"}""")
                            )
                        )
                    )
                )
            )
        )

        val json = mapper.readTree(mapper.writeValueAsString(chunk))
        val toolCall = json["choices"][0]["delta"]["tool_calls"][0]
        assertEquals("call_1", toolCall["id"].asText())
        assertEquals("function", toolCall["type"].asText())
        assertEquals("read_file", toolCall["function"]["name"].asText())
        assertEquals("""{"path":"README.md"}""", toolCall["function"]["arguments"].asText())
    }

    @Test
    fun `final chunk keeps empty delta object`() {
        val chunk = ChatCompletionChunkResponse(
            id = "chatcmpl-test",
            created = 123L,
            model = "gpt-4o",
            choices = listOf(
                ChatChunkChoice(
                    index = 0,
                    delta = ChatChunkDelta(),
                    finish_reason = "stop"
                )
            )
        )

        val json = mapper.readTree(mapper.writeValueAsString(chunk))
        val delta = json["choices"][0]["delta"]
        assertTrue(delta.isObject)
        assertEquals(0, delta.size())
        assertEquals("stop", json["choices"][0]["finish_reason"].asText())
    }
}
