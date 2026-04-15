package com.intelliaibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intelliaibridge.copilot.CopilotBridge
import com.intelliaibridge.settings.IntelliAiBridgeSettings
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * Handles `/v1/chat/completions` for both streaming and non-streaming modes.
 */
internal class GatewayChatCompletions(
    private val copilotBridge: CopilotBridge,
    private val settingsProvider: () -> IntelliAiBridgeSettings,
    private val projectResolver: GatewayProjectResolver,
    private val buildPrompt: (List<ChatMessage>, String) -> String,
    private val parseXmlToolCalls: (String) -> ParsedXmlTools,
    private val log: (String) -> Unit
) {
    suspend fun handle(call: ApplicationCall, request: ChatCompletionRequest) {
        val settings = settingsProvider()
        val project = projectResolver.resolveProject(call)
        if (project == null) {
            call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable, mapOf("error" to "No open project found"))
            return
        }

        val prompt = buildPrompt(
            request.messages,
            settings.defaultSystemPrompt
        )
        val modelIdStr = (request.model?.takeIf { it.isNotBlank() }
            ?: settings.defaultModel.takeIf { it.isNotBlank() })
            ?: "default"

        var sessionHandle: CopilotBridge.SessionHandle? = null

        try {
            sessionHandle = copilotBridge.prepareSession(
                project = project,
                requestedModel = request.model,
                defaultModel = settings.defaultModel
            )

            log("Sending prompt via ConversationService (model: $modelIdStr, session: ${sessionHandle.sessionId})")

            if (request.stream) {
                val completedText = collectAssistantReply(sessionHandle, prompt, logEvents = true)
                log("Copilot Completed. Total length: ${completedText.length}")
                val parsed = parseXmlToolCalls(completedText)
                respondStreaming(call, request, modelIdStr, parsed, completedText)
            } else {
                val fullContent = collectAssistantReply(sessionHandle, prompt, logEvents = false)
                respondNonStreaming(call, modelIdStr, fullContent)
            }
        } catch (e: IllegalStateException) {
            call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Copilot unavailable")))
        } finally {
            if (sessionHandle != null) {
                copilotBridge.cleanupSession(sessionHandle, log)
            }
        }
    }

    private suspend fun collectAssistantReply(
        sessionHandle: CopilotBridge.SessionHandle,
        prompt: String,
        logEvents: Boolean
    ): String {
        val completion = CompletableDeferred<String>()
        var fullContent = ""

        copilotBridge.sendMessage(sessionHandle, prompt) { event ->
            if (logEvents) {
                log("Event received: ${event.type}")
            }
            when (event) {
                is CopilotBridge.Event.Progress -> {
                    event.reply?.let { fullContent += it }
                }
                is CopilotBridge.Event.Complete -> {
                    event.reply?.let { if (fullContent.isEmpty()) fullContent = it }
                    if (fullContent.isEmpty()) {
                        val finalSessionReply = copilotBridge.extractVisibleAssistantText(sessionHandle)
                        if (!finalSessionReply.isNullOrBlank()) {
                            fullContent = finalSessionReply
                        } else {
                            log("No visible assistant text in session message. ${copilotBridge.describeLatestResponseMessage(sessionHandle)}")
                        }
                    }
                    completion.complete(fullContent)
                }
                is CopilotBridge.Event.Error -> {
                    completion.completeExceptionally(Exception(event.message))
                }
                is CopilotBridge.Event.Other -> {
                }
            }
        }

        return completion.await()
    }

    private suspend fun respondStreaming(
        call: ApplicationCall,
        request: ChatCompletionRequest,
        modelIdStr: String,
        parsed: ParsedXmlTools,
        completedText: String
    ) {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val requestId = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            val mapper = jacksonObjectMapper()

            fun writeChunk(chunk: Any) {
                write("data: ${mapper.writeValueAsString(chunk)}\n\n")
            }

            if (request.tools != null && request.tools.isNotEmpty()) {
                if (parsed.cleanedText.isNotEmpty()) {
                    writeChunk(
                        ChatCompletionChunkResponse(
                            id = requestId,
                            created = created,
                            model = modelIdStr,
                            choices = listOf(
                                ChatChunkChoice(
                                    index = 0,
                                    delta = ChatChunkDelta(content = parsed.cleanedText),
                                    finish_reason = null
                                )
                            )
                        )
                    )
                }
                parsed.toolCalls.forEachIndexed { index, tc ->
                    writeChunk(
                        ChatCompletionChunkResponse(
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
                    )
                }
            } else if (completedText.isNotEmpty()) {
                writeChunk(
                    ChatCompletionChunkResponse(
                        id = requestId,
                        created = created,
                        model = modelIdStr,
                        choices = listOf(
                            ChatChunkChoice(
                                index = 0,
                                delta = ChatChunkDelta(content = completedText),
                                finish_reason = null
                            )
                        )
                    )
                )
            }

            val finishReason = if (parsed.toolCalls.isNotEmpty()) "tool_calls" else "stop"
            writeChunk(
                ChatCompletionChunkResponse(
                    id = requestId,
                    created = created,
                    model = modelIdStr,
                    choices = listOf(
                        ChatChunkChoice(
                            index = 0,
                            delta = ChatChunkDelta(),
                            finish_reason = finishReason
                        )
                    )
                )
            )
            write("data: [DONE]\n\n")
            flush()
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
