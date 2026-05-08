package com.aibridge.server

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * OpenAI-compatible request payload for `/v1/chat/completions`.
 *
 * Unknown fields are intentionally ignored to maximize compatibility with SDKs
 * that send additional metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionRequest(
    val model: String?,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val tools: List<ChatTool>? = null,
    val tool_choice: Any? = null,
    val max_tokens: Int? = null,
    val temperature: Double? = null
)

/**
 * OpenAI-compatible request payload for legacy `/v1/completions`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletionsRequest(
    val model: String?,
    val prompt: Any?, 
    val stream: Boolean = false,
    val max_tokens: Int? = null,
    val temperature: Double? = null
)

/**
 * Chat message envelope used by chat completion requests and responses.
 *
 * `content` accepts both string and content-part-array formats via
 * [ContentDeserializer].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessage(
    val role: String,
    @JsonDeserialize(using = ContentDeserializer::class)
    val content: String?,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null
)

/**
 * Deserializes OpenAI message `content` into a plain text string.
 *
 * Supported input:
 * - Plain string
 * - Array of parts containing either direct strings or `{ \"text\": ... }` objects
 */
class ContentDeserializer : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
        val node: JsonNode = p.codec.readTree(p)
        return when {
            node.isTextual -> node.asText()
            node.isArray -> {
                val sb = StringBuilder()
                for (part in node) {
                    if (part.isTextual) sb.append(part.asText())
                    else if (part.has("text")) sb.append(part.get("text").asText())
                }
                sb.toString()
            }
            node.isNull -> ""
            else -> node.toString()
        }
    }
}

/**
 * OpenAI-compatible tool definition wrapper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatTool(
    val type: String = "function",
    val function: ChatFunction
)

/**
 * Function schema descriptor used in OpenAI-compatible `tools` payloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatFunction(
    val name: String,
    val description: String? = null,
    val parameters: Map<String, Any>? = null,
    val strict: Boolean? = null
)

/**
 * Tool call emitted by assistant responses.
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

/**
 * Function invocation payload containing function name and JSON arguments.
 */
data class FunctionCall(
    val name: String,
    val arguments: String
)

/**
 * Non-streaming OpenAI-style chat completion response.
 */
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage? = null
)

/**
 * Single response candidate in a non-streaming chat completion response.
 */
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String? = "stop"
)

/**
 * Optional token accounting section of non-streaming responses.
 */
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

/**
 * Streaming OpenAI-style chunk response.
 */
data class ChatCompletionChunkResponse(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>
)

/**
 * Choice payload for a streaming completion chunk.
 */
data class ChatChunkChoice(
    val index: Int,
    val delta: ChatChunkDelta,
    val finish_reason: String? = null
)

/**
 * Incremental delta payload for streaming responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatChunkDelta(
    val content: String? = null,
    val tool_calls: List<ChatChunkToolCall>? = null
)

/**
 * Incremental tool call payload emitted as part of streaming chunks.
 */
data class ChatChunkToolCall(
    val index: Int,
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

/**
 * OpenAI-compatible error response.
 */
data class OpenAiErrorResponse(val error: OpenAiError)

data class OpenAiError(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null
)

/**
 * OpenAI-compatible model descriptor used by `/v1/models`.
 */
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val owned_by: String = "jetbrains",
    val label: String? = null
)
