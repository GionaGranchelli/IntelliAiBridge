package com.aibridge.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiBridgeGatewayBuildPromptTest {
    @Test
    fun `build injects default system prompt when request has no system message`() {
        val result = GatewayPromptBuilder.build(
            messages = listOf(ChatMessage("user", "Tell me a joke.")),
            defaultSystemPrompt = "Keep answers concise."
        )

        assertEquals(
            "System instruction:\nKeep answers concise.\n\nTell me a joke.",
            result
        )
    }

    @Test
    fun `build does not inject default system prompt when request already has system message`() {
        val result = GatewayPromptBuilder.build(
            messages = listOf(
                ChatMessage("system", "Use markdown."),
                ChatMessage("user", "Summarize this.")
            ),
            defaultSystemPrompt = "Keep answers concise."
        )

        assertEquals(
            "System instruction:\nUse markdown.\n\nSummarize this.",
            result
        )
    }

    @Test
    fun `build keeps user and assistant roles formatting`() {
        val result = GatewayPromptBuilder.build(
            messages = listOf(
                ChatMessage("user", "Question"),
                ChatMessage("assistant", "Earlier answer")
            ),
            defaultSystemPrompt = ""
        )

        assertEquals(
            "Question\nPrevious assistant response:\nEarlier answer",
            result
        )
    }

    @Test
    fun `build handles tool messages with content and tool_call_id`() {
        val result = GatewayPromptBuilder.build(
            messages = listOf(
                ChatMessage("user", "Read the file"),
                ChatMessage("tool", "File contents here", tool_call_id = "call_123")
            ),
            defaultSystemPrompt = ""
        )

        assert(result.contains("Tool result:"))
        assert(result.contains("File contents here"))
        assert(result.contains("(for tool call: call_123)"))
    }

    @Test
    fun `build handles empty default system prompt`() {
        val result = GatewayPromptBuilder.build(
            messages = listOf(ChatMessage("user", "Hello")),
            defaultSystemPrompt = ""
        )

        assertEquals("Hello", result)
    }
}
