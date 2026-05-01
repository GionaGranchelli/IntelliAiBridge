package com.aibridge.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiBridgeGatewayBuildPromptTest {
    @Test
    fun `buildPrompt injects default system prompt when request has no system message`() {
        val result = invokeBuildPrompt(
            messages = listOf(ChatMessage("user", "Tell me a joke.")),
            defaultSystemPrompt = "Keep answers concise."
        )

        assertEquals(
            "System instruction:\nKeep answers concise.\n\nTell me a joke.",
            result
        )
    }

    @Test
    fun `buildPrompt does not inject default system prompt when request already has system message`() {
        val result = invokeBuildPrompt(
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
    fun `buildPrompt keeps user and assistant roles formatting`() {
        val result = invokeBuildPrompt(
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

    private fun invokeBuildPrompt(messages: List<ChatMessage>, defaultSystemPrompt: String): String {
        val gateway = AiBridgeGateway()
        val method = gateway.javaClass.getDeclaredMethod("buildPrompt", List::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(gateway, messages, defaultSystemPrompt) as String
    }
}
