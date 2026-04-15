package com.intelliaibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class OpenAiCompatibilityRequestDeserializationTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `chat completion request accepts openai tools strict and unknown fields`() {
        val json = """
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "user", "content": "hello"}
              ],
              "stream": true,
              "parallel_tool_calls": true,
              "response_format": {"type": "json_object"},
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "read_file",
                    "description": "Read a file",
                    "strict": true,
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"}
                      },
                      "required": ["path"]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val request: ChatCompletionRequest = mapper.readValue(json)

        assertEquals("gpt-4o", request.model)
        assertEquals(true, request.stream)
        assertNotNull(request.tools)
        assertEquals(1, request.tools!!.size)
        assertEquals("read_file", request.tools!![0].function.name)
        assertEquals(true, request.tools!![0].function.strict)
    }
}
