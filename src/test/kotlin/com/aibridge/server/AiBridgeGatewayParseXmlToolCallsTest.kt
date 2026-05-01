package com.aibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiBridgeGatewayParseXmlToolCallsTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `parseXmlToolCalls extracts tool calls and removes xml block`() {
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

        val result = invokeParseXmlToolCalls(input)
        val cleanedText = getCleanedText(result)
        val toolCalls = getToolCalls(result)

        assertTrue(cleanedText.contains("Sure, I can do that."))
        assertTrue(cleanedText.contains("Done."))
        assertTrue(!cleanedText.contains("<function_calls>"))
        assertEquals(2, toolCalls.size)

        val first = toolCalls[0]
        val firstFn = getFunctionName(first)
        val firstArgs = getFunctionArguments(first)
        assertEquals("read_file", firstFn)
        assertEquals("README.md", mapper.readTree(firstArgs).get("path").asText())

        val second = toolCalls[1]
        val secondFn = getFunctionName(second)
        val secondArgs = getFunctionArguments(second)
        val secondJson = mapper.readTree(secondArgs)
        assertEquals("grep", secondFn)
        assertEquals("AiBridge", secondJson.get("pattern").asText())
        assertEquals("src/main", secondJson.get("path").asText())
    }

    @Test
    fun `parseXmlToolCalls returns input unchanged when no function block exists`() {
        val input = "Just answer normally without tool calls."

        val result = invokeParseXmlToolCalls(input)
        val cleanedText = getCleanedText(result)
        val toolCalls = getToolCalls(result)

        assertEquals(input, cleanedText)
        assertTrue(toolCalls.isEmpty())
    }

    private fun invokeParseXmlToolCalls(input: String): Any {
        val gateway = AiBridgeGateway()
        val method = gateway.javaClass.getDeclaredMethod("parseXmlToolCalls", String::class.java)
        method.isAccessible = true
        return method.invoke(gateway, input)
    }

    private fun getCleanedText(result: Any): String {
        return result.javaClass.getMethod("getCleanedText").invoke(result) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun getToolCalls(result: Any): List<Any> {
        return result.javaClass.getMethod("getToolCalls").invoke(result) as List<Any>
    }

    private fun getFunctionName(toolCall: Any): String {
        val function = toolCall.javaClass.getMethod("getFunction").invoke(toolCall)
        return function.javaClass.getMethod("getName").invoke(function) as String
    }

    private fun getFunctionArguments(toolCall: Any): String {
        val function = toolCall.javaClass.getMethod("getFunction").invoke(toolCall)
        return function.javaClass.getMethod("getArguments").invoke(function) as String
    }
}
