package com.intelliaibridge.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContentDeserializerTest {
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    @Test
    fun `deserializes textual content`() {
        val msg = mapper.readValue<ChatMessage>(
            """{"role":"user","content":"hello"}"""
        )
        assertEquals("hello", msg.content)
    }

    @Test
    fun `deserializes array content with text parts`() {
        val msg = mapper.readValue<ChatMessage>(
            """
            {
              "role":"user",
              "content":[
                {"type":"text","text":"hello "},
                {"type":"text","text":"world"},
                "!"
              ]
            }
            """.trimIndent()
        )
        assertEquals("hello world!", msg.content)
    }

    @Test
    fun `keeps null content as null`() {
        val msg = mapper.readValue<ChatMessage>(
            """{"role":"assistant","content":null}"""
        )
        assertEquals(null, msg.content)
    }
}
