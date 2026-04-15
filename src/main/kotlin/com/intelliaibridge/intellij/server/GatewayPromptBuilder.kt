package com.intelliaibridge.intellij.server

/**
 * Flattens OpenAI chat messages into a single prompt accepted by Copilot
 * conversation APIs, optionally injecting a default system prompt.
 */
internal object GatewayPromptBuilder {
    fun build(messages: List<ChatMessage>, defaultSystemPrompt: String): String {
        val promptBuilder = StringBuilder()
        val hasSystemMessage = messages.any { it.role == "system" }

        if (!hasSystemMessage && defaultSystemPrompt.isNotBlank()) {
            promptBuilder.append("System instruction:\n").append(defaultSystemPrompt).append("\n\n")
        }

        messages.forEach { msg ->
            when (msg.role) {
                "system" -> msg.content?.takeIf { it.isNotBlank() }?.let {
                    promptBuilder.append("System instruction:\n").append(it).append("\n\n")
                }
                "user" -> msg.content?.takeIf { it.isNotBlank() }?.let {
                    promptBuilder.append(it).append("\n")
                }
                "assistant" -> msg.content?.takeIf { it.isNotBlank() }?.let {
                    promptBuilder.append("Previous assistant response:\n").append(it).append("\n")
                }
            }
        }

        return promptBuilder.toString().trim()
    }
}
