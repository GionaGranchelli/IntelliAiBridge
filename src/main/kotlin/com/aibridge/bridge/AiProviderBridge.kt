package com.aibridge.bridge

import com.intellij.openapi.project.Project

/**
 * Common interface for AI providers (GitHub Copilot, Google Gemini, etc.).
 */
interface AiProviderBridge {
    /** Lists user-available models. */
    fun listAvailableModels(project: Project): List<AvailableModel>

    /** Creates and configures a session for one request. */
    suspend fun prepareSession(project: Project, requestedModel: String?, defaultModel: String): AiSessionHandle

    /** Sends prompt text and forwards mapped progress events. */
    suspend fun sendMessage(handle: AiSessionHandle, prompt: String, onEvent: (AiEvent) -> Unit)

    /** Extracts user-visible assistant text from the session. */
    fun extractVisibleAssistantText(handle: AiSessionHandle): String?

    /** Best-effort cleanup for request-scoped sessions. */
    suspend fun cleanupSession(handle: AiSessionHandle, log: (String) -> Unit)
}

/** Marker interface for provider-specific session handles. */
interface AiSessionHandle

/** Stream of normalized events emitted while processing a prompt. */
sealed class AiEvent(open val type: String) {
    data class Progress(val reply: String?, val kind: String?) : AiEvent("Progress")
    data class Complete(val reply: String?) : AiEvent("Complete")
    data class Error(val message: String) : AiEvent("Error")
    data class Other(override val type: String) : AiEvent(type)
}

/** Model descriptor. */
data class AvailableModel(
    val id: String,
    val label: String
)
