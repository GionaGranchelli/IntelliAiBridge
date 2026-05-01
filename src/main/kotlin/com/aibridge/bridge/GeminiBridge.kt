package com.aibridge.bridge

import com.aibridge.settings.AiBridgeSettings
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GeminiBridge : AiProviderBridge {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val mapper = jacksonObjectMapper()

    data class GeminiSessionHandle(
        val apiKey: String,
        val modelId: String
    ) : AiSessionHandle

    override fun listAvailableModels(project: Project): List<AvailableModel> {
        return listOf(
            AvailableModel("gemini-1.5-pro", "Gemini 1.5 Pro"),
            AvailableModel("gemini-1.5-flash", "Gemini 1.5 Flash"),
            AvailableModel("gemini-2.0-flash-exp", "Gemini 2.0 Flash (Exp)"),
            AvailableModel("gemini-1.0-pro", "Gemini 1.0 Pro")
        )
    }

    override suspend fun prepareSession(project: Project, requestedModel: String?, defaultModel: String): AiSessionHandle {
        val settings = AiBridgeSettings.instance
        val apiKey = "" // settings.getStoredGeminiApiKey() - Hidden for now
        if (apiKey.isBlank()) {
            throw IllegalStateException("Gemini API Key not configured in settings")
        }
        val modelId = (requestedModel?.takeIf { it.isNotBlank() } ?: defaultModel.takeIf { it.isNotBlank() }) ?: "gemini-1.5-flash"
        return GeminiSessionHandle(apiKey, modelId)
    }

    override suspend fun sendMessage(handle: AiSessionHandle, prompt: String, onEvent: (AiEvent) -> Unit) {
        val geminiHandle = handle as? GeminiSessionHandle ?: return
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${geminiHandle.modelId}:streamGenerateContent?alt=sse&key=${geminiHandle.apiKey}"
        
        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
            if (response.statusCode() != 200) {
                onEvent(AiEvent.Error("Gemini API error: ${response.statusCode()}"))
                return
            }

            var fullText = ""
            response.body().forEach { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isNotEmpty()) {
                        try {
                            val node = mapper.readTree(data)
                            val text = node.at("/candidates/0/content/parts/0/text").asText("")
                            if (text.isNotEmpty()) {
                                fullText += text
                                onEvent(AiEvent.Progress(text, null))
                            }
                        } catch (e: Exception) {
                            // Skip invalid chunks
                        }
                    }
                }
            }
            onEvent(AiEvent.Complete(fullText))
        } catch (e: Exception) {
            onEvent(AiEvent.Error("Gemini connection failed: ${e.message}"))
        }
    }

    override fun extractVisibleAssistantText(handle: AiSessionHandle): String? {
        return null // Gemini bridge doesn't need separate extraction as it collects during streaming
    }

    override suspend fun cleanupSession(handle: AiSessionHandle, log: (String) -> Unit) {
        // No-op for stateless API bridge
    }
}
