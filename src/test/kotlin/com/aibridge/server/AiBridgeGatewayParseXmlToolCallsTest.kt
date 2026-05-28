package com.aibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiBridgeGatewayParseXmlToolCallsTest {
    private val mapper = jacksonObjectMapper()
    private val parser = GatewayXmlToolCallParser()

    @Test
    fun `parse extracts tool calls and removes xml block`() {
        val input = """
            Sure, I can do that.
            <function_calls>
              <invoke name="read_file">
                <parameter name="path">README.md</parameter>
              </invoke>
              <invoke name="grep">
                <parameter name="pattern">AiBridge</parameter>
                <parameter name="path">src/main</parameter>
              </invoke>
            </function_calls>
            Done.
        """.trimIndent()

        val result = parser.parse(input)
        val cleanedText = result.cleanedText
        val toolCalls = result.toolCalls

        assertTrue(cleanedText.contains("Sure, I can do that."))
        assertTrue(cleanedText.contains("Done."))
        assertTrue(!cleanedText.contains("<function_calls>"))
        assertEquals(2, toolCalls.size)

        val first = toolCalls[0]
        assertEquals("read_file", first.function.name)
        assertEquals("README.md", mapper.readTree(first.function.arguments).get("path").asText())

        val second = toolCalls[1]
        val secondJson = mapper.readTree(second.function.arguments)
        assertEquals("grep", second.function.name)
        assertEquals("AiBridge", secondJson.get("pattern").asText())
        assertEquals("src/main", secondJson.get("path").asText())
    }

    @Test
    fun `parse returns input unchanged when no function block exists`() {
        val input = "Just answer normally without tool calls."

        val result = parser.parse(input)

        assertEquals(input, result.cleanedText)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `parse handles malformed xml gracefully`() {
        val input = """
            Some text.
            <function_calls>
              <invoke name="bad">
            </function_calls>
        """.trimIndent()

        val result = parser.parse(input)
        assertTrue(result.cleanedText.contains("Some text."))
        // Malformed XML should not crash, at most return partial results
    }

    @Test
    fun `parse handles multiple function_call blocks`() {
        val input = """
            <function_calls>
              <invoke name="first">
                <parameter name="a">1</parameter>
              </invoke>
            </function_calls>
            Middle text.
            <function_calls>
              <invoke name="second">
                <parameter name="b">2</parameter>
              </invoke>
            </function_calls>
        """.trimIndent()

        val result = parser.parse(input)
        assertEquals(2, result.toolCalls.size)
        assertEquals("first", result.toolCalls[0].function.name)
        assertEquals("second", result.toolCalls[1].function.name)
        assertTrue(!result.cleanedText.contains("<function_calls>"))
    }

    @Test
    fun `parse handles empty function_calls block`() {
        val input = "Text before. <function_calls></function_calls> Text after."

        val result = parser.parse(input)

        // Empty blocks are left in the text (no tool calls to extract)
        assertTrue(result.cleanedText.contains("Text before."))
        assertTrue(result.cleanedText.contains("Text after."))
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `parse handles invoke without name attribute`() {
        val input = """
            <function_calls>
              <invoke>
                <parameter name="x">y</parameter>
              </invoke>
            </function_calls>
        """.trimIndent()

        val result = parser.parse(input)
        // invokes without name should be skipped
        assertTrue(result.toolCalls.isEmpty() || result.toolCalls.all { it.function.name.isNotBlank() })
    }
}
