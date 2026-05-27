package com.aibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.aibridge.bridge.AiEvent
import com.aibridge.bridge.AiProviderBridge
import com.aibridge.bridge.AiSessionHandle
import com.aibridge.settings.AiBridgeSettings
import com.intellij.openapi.application.EDT
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles `/v1/chat/completions` for both streaming and non-streaming modes.
 */
internal class GatewayChatCompletions(
    private val bridgeProvider: () -> AiProviderBridge,
    private val settingsProvider: () -> AiBridgeSettings,
    private val projectResolver: GatewayProjectResolver,
    private val buildPrompt: (List<ChatMessage>, String) -> String,
    private val parseXmlToolCalls: (String) -> ParsedXmlTools,
    private val log: (String) -> Unit
) {
    suspend fun handle(call: ApplicationCall, request: ChatCompletionRequest) {
        val settings = settingsProvider()
        val project = projectResolver.resolveProject(call)
        if (project == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No open project found"))
            return
        }

        val prompt = buildPrompt(
            request.messages,
            settings.defaultSystemPrompt
        )
        val modelIdStr = (request.model?.takeIf { it.isNotBlank() }
            ?: settings.defaultModel.takeIf { it.isNotBlank() })
            ?: "default"

        var sessionHandle: AiSessionHandle? = null
        val bridge = bridgeProvider()

        try {
            sessionHandle = bridge.prepareSession(
                project = project,
                requestedModel = request.model,
                defaultModel = settings.defaultModel
            )

            log("Sending prompt via ${settings.activeProvider.name} (model: $modelIdStr)")

            if (request.stream) {
                respondStreaming(call, request, modelIdStr, bridge, sessionHandle, prompt)
            } else {
                val fullContent = collectAssistantReply(bridge, sessionHandle, prompt)
                respondNonStreaming(call, modelIdStr, fullContent)
            }
        } catch (e: Exception) {
            log("Error handling chat completion: ${e.message}")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Provider unavailable")))
            }
        } finally {
            if (sessionHandle != null) {
                bridge.cleanupSession(sessionHandle, log)
            }
        }
    }

    /**
     * Collects the full assistant reply for non-streaming responses.
     * Uses CompletableDeferred to bridge callback-based events to coroutines.
     */
    private suspend fun collectAssistantReply(
        bridge: AiProviderBridge,
        sessionHandle: AiSessionHandle,
        prompt: String
    ): String {
        val completion = CompletableDeferred<String>()
        var fullContent = ""

        bridge.sendMessage(sessionHandle, prompt) { event ->
            when (event) {
                is AiEvent.Progress -> {
                    event.reply?.let { fullContent += it }
                }
                is AiEvent.Complete -> {
                    event.reply?.let { if (fullContent.isEmpty()) fullContent = it }
                    if (fullContent.isEmpty()) {
                        val finalSessionReply = bridge.extractVisibleAssistantText(sessionHandle)
                        if (!finalSessionReply.isNullOrBlank()) {
                            fullContent = finalSessionReply
                        }
                    }
                    completion.complete(fullContent)
                }
                is AiEvent.Error -> {
                    completion.completeExceptionally(Exception(event.message))
                }
                is AiEvent.Other -> {}
            }
        }

        return completion.await()
    }

    /**
     * Real SSE streaming: uses a [Channel] to bridge callback-based
     * [AiEvent.Progress] events from [AiProviderBridge.sendMessage] (which
     * runs on EDT) to Ktor's [respondTextWriter], emitting SSE chunks as
     * they arrive rather than after the entire response is collected.
     */
    private suspend fun respondStreaming(
        call: ApplicationCall,
        request: ChatCompletionRequest,
        modelIdStr: String,
        bridge: AiProviderBridge,
        sessionHandle: AiSessionHandle,
        prompt: String
    ) {
        call.response.cacheControl(CacheControl.NoCache(null))

        // Create channel before respondTextWriter so the bridge can
        // start sending events into it while we prepare the response.
        val channel = Channel<String>(Channel.BUFFERED)

        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // Capture the Writer receiver in a local val so it remains
            // accessible inside nested coroutineScope where 'this' is
            // the CoroutineScope rather than the Writer.
            val writer = this
            val requestId = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            val mapper = jacksonObjectMapper()

            // Helper: write a single SSE data frame with optional content
            // and finish_reason. null content + non-null finish_reason =
            // final empty-delta chunk.
            fun writeChunk(content: String?, finishReason: String?) {
                val chunk = ChatCompletionChunkResponse(
                    id = requestId,
                    created = created,
                    model = modelIdStr,
                    choices = listOf(
                        ChatChunkChoice(
                            index = 0,
                            delta = ChatChunkDelta(content = content),
                            finish_reason = finishReason
                        )
                    )
                )
                writer.write("data: ${mapper.writeValueAsString(chunk)}\n\n")
                writer.flush()
            }

            coroutineScope {
                // Launch the bridge call on EDT (Copilot requires it).
                // This runs concurrently with our channel-reading loop
                // below, so chunks are written as soon as they arrive.
                launch(Dispatchers.EDT) {
                    bridge.sendMessage(sessionHandle, prompt) { event ->
                        when (event) {
                            is AiEvent.Progress -> {
                                event.reply?.let { channel.trySend(it) }
                            }
                            is AiEvent.Complete -> {
                                channel.close()
                            }
                            is AiEvent.Error -> {
                                channel.close(Exception(event.message))
                            }
                            is AiEvent.Other -> {}
                        }
                    }
                }

                // Read chunks from the channel as they arrive and emit
                // SSE data frames immediately — true real-time streaming.
                var accumulated = ""
                for (chunk in channel) {
                    accumulated += chunk
                    writeChunk(chunk, null)
                }

                // All content has arrived. Parse tool calls from the
                // accumulated text and emit tool_calls chunks if any,
                // matching OpenAI's streaming behavior where tool_calls
                // appear after content chunks.
                val parsed = parseXmlToolCalls(accumulated)
                parsed.toolCalls.forEachIndexed { index, tc ->
                    val toolChunk = ChatCompletionChunkResponse(
                        id = requestId,
                        created = created,
                        model = modelIdStr,
                        choices = listOf(
                            ChatChunkChoice(
                                index = 0,
                                delta = ChatChunkDelta(
                                    tool_calls = listOf(
                                        ChatChunkToolCall(
                                            index = index,
                                            id = tc.id,
                                            function = tc.function
                                        )
                                    )
                                ),
                                finish_reason = null
                            )
                        )
                    )
                    writer.write("data: ${mapper.writeValueAsString(toolChunk)}\n\n")
                    writer.flush()
                }

                // Final chunk with finish_reason and the [DONE] marker
                val finishReason = if (parsed.toolCalls.isNotEmpty()) "tool_calls" else "stop"
                writeChunk(null, finishReason)
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
    }

    private suspend fun respondNonStreaming(
        call: ApplicationCall,
        modelIdStr: String,
        fullContent: String
    ) {
        val parsed = parseXmlToolCalls(fullContent)
        val responseMessage = if (parsed.toolCalls.isNotEmpty()) {
            ChatMessage("assistant", null, tool_calls = parsed.toolCalls)
        } else {
            ChatMessage("assistant", fullContent)
        }

        call.respond(
            ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = modelIdStr,
                choices = listOf(
                    ChatChoice(
                        0,
                        responseMessage,
                        finish_reason = if (parsed.toolCalls.isNotEmpty()) "tool_calls" else "stop"
                    )
                )
            )
        )
    }
}
