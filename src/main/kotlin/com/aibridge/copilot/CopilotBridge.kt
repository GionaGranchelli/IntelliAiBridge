package com.aibridge.copilot

import com.aibridge.bridge.AiEvent
import com.aibridge.bridge.AiProviderBridge
import com.aibridge.bridge.AiSessionHandle
import com.aibridge.bridge.AvailableModel
import com.github.copilot.agent.CopilotAgentDataKeys
import com.github.copilot.agent.chatMode.ChatModeService
import com.github.copilot.agent.conversation.ConversationProgressHandler as AgentProgressHandler
import com.github.copilot.agent.conversation.CopilotAgentConversationProgressEvent
import com.github.copilot.agent.message.CopilotAgentMessageType
import com.github.copilot.agent.message.MessageContent
import com.github.copilot.agent.session.CopilotAgentSession
import com.github.copilot.agent.session.CopilotAgentSessionController
import com.github.copilot.agent.session.CopilotAgentSessionManager
import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode as RpcChatMode
import com.github.copilot.model.CompositeModelService
import com.github.copilot.model.ModelScope
import com.github.copilot.model.UserSelectedModelService
import com.github.copilot.model.toModelId
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter over GitHub Copilot IDE APIs used by AiBridge.
 *
 * It isolates plugin-specific session/model behavior from HTTP gateway logic.
 */
class CopilotBridge : AiProviderBridge {
    /**
     * Handle for a live Copilot session/context used for one request.
     */
    class SessionHandle internal constructor(
        internal val sessionManager: CopilotAgentSessionManager,
        internal val session: CopilotAgentSession,
        internal val controller: CopilotAgentSessionController,
        internal val dataContext: DataContext,
        val modeId: String?
    ) : AiSessionHandle {
        val sessionId: String
            get() = session.id
    }

    /** Lists user-available models, preferring model catalog over chat-mode fallback. */
    override fun listAvailableModels(project: Project): List<AvailableModel> {
        val fromCatalog = listFromCompositeModelCatalog()
        if (fromCatalog.isNotEmpty()) {
            return fromCatalog
        }
        return listFromChatModes(project)
    }

    /**
     * Creates and configures a Copilot session for one request.
     *
     * The bridge prefers Agent mode and sets a requested/default model when available.
     */
    override suspend fun prepareSession(project: Project, requestedModel: String?, defaultModel: String): AiSessionHandle {
        val sessionManager = project.getService(CopilotAgentSessionManager::class.java)
            ?: throw IllegalStateException("GitHub Copilot Session Manager not found")

        val session = sessionManager.createSession { }
        sessionManager.activateSession(session)
        val controller = sessionManager.getCurrentSessionController()
            ?: throw IllegalStateException("Copilot session controller unavailable")

        val chatModeService = project.getService(ChatModeService::class.java)
        val selectedMode = selectPreferredMode(chatModeService)
        if (selectedMode != null) {
            trySetSelectedChatMode(project, selectedMode)
        }

        val modelIdStr = (requestedModel?.takeIf { it.isNotBlank() }
            ?: defaultModel.takeIf { it.isNotBlank() })
            ?: "default"

        val requestedModelId = if (modelIdStr != "default") {
            val (modelName, providerName) = ChatMode.parseModel(modelIdStr)
            val modelService = ApplicationManager.getApplication().getService(CompositeModelService::class.java)
            val requested = modelService?.let { findModel(it, modelName, providerName) }
            if (requested != null) {
                val scope = if (selectedMode?.isAgentKind() == true) ModelScope.AgentPanel else ModelScope.ChatPanel
                project.getService(UserSelectedModelService::class.java)?.setSelectedModel(scope, requested)
                requested.toModelId()
            } else null
        } else null

        val builder = SimpleDataContext.builder()
            .setParent(SimpleDataContext.builder().build())
        if (requestedModelId != null) {
            builder.add(CopilotAgentDataKeys.MODEL_ID, requestedModelId)
        }

        return SessionHandle(sessionManager, session, controller, builder.build(), selectedMode?.id)
    }

    /** Sends prompt text and forwards mapped Copilot progress events. */
    override suspend fun sendMessage(handle: AiSessionHandle, prompt: String, onEvent: (AiEvent) -> Unit) {
        val sessionHandle = handle as SessionHandle
        val handler = object : AgentProgressHandler<CopilotAgentConversationProgressEvent> {
            override fun on(event: CopilotAgentConversationProgressEvent) {
                val mapped = when (event) {
                    is CopilotAgentConversationProgressEvent.Progress -> AiEvent.Progress(event.progress.reply, event.progress.kind)
                    is CopilotAgentConversationProgressEvent.Complete -> AiEvent.Complete(event.complete.reply)
                    is CopilotAgentConversationProgressEvent.Error -> AiEvent.Error(event.error.message ?: "Unknown Copilot error")
                    else -> AiEvent.Other(event.javaClass.simpleName)
                }
                onEvent(mapped)
            }
        }
        withContext(Dispatchers.EDT) {
            sessionHandle.controller.sendMessage(prompt, sessionHandle.dataContext, handler)
        }
    }

    /**
     * Extracts user-visible assistant text from the latest Copilot response message.
     */
    override fun extractVisibleAssistantText(handle: AiSessionHandle): String? {
        val sessionHandle = handle as SessionHandle
        val responseMessage = sessionHandle.session.messages
            .asReversed()
            .firstOrNull { it.type == CopilotAgentMessageType.RESPONSE }
            ?: return null

        if (!responseMessage.stringContent.isNullOrBlank()) {
            return responseMessage.stringContent.trim()
        }

        val chunks = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val contents = responseMessage.contents as Iterable<MessageContent>
        for (content in contents) {
            chunks.addAll(extractTextFromMessageContent(content))
        }

        return chunks
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Returns compact debug metadata for the latest response when visible text extraction fails.
     */
    fun describeLatestResponseMessage(handle: SessionHandle): String {
        val responseMessage = handle.session.messages
            .asReversed()
            .firstOrNull { it.type == CopilotAgentMessageType.RESPONSE }
            ?: return "No RESPONSE message found"

        @Suppress("UNCHECKED_CAST")
        val contents = responseMessage.contents as Iterable<MessageContent>
        val contentTypes = contents.map { it::class.simpleName ?: it.javaClass.simpleName }
        val preview = extractVisibleAssistantText(handle)?.take(120)
        return "response.stringContent.length=${responseMessage.stringContent?.length ?: 0}, contentTypes=$contentTypes, extractedPreview=${preview ?: "<none>"}"
    }

    /** Best-effort cleanup for request-scoped Copilot sessions. */
    override suspend fun cleanupSession(handle: AiSessionHandle, log: (String) -> Unit) {
        val sessionHandle = handle as SessionHandle
        try {
            sessionHandle.sessionManager.deleteSession(sessionHandle.session.id)
        } catch (e: Exception) {
            log("Failed to cleanup session ${sessionHandle.session.id}: ${e.message}")
        }
    }

    private fun extractTextFromMessageContent(content: MessageContent): List<String> {
        return when (content) {
            is MessageContent.Markdown -> listOf(content.data.text)
            is MessageContent.AgentRound -> buildList {
                content.agentRound.reply?.takeIf { it.isNotBlank() }?.let { add(it) }
                content.agentRound.toolCalls?.forEach { tc ->
                    tc.progressMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
                    tc.inputMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
                    tc.error?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            is MessageContent.Thinking -> listOf(content.content)
            is MessageContent.Steps -> content.steps.mapNotNull { step ->
                when {
                    !step.description.isNullOrBlank() -> step.description
                    !step.title.isNullOrBlank() -> step.title
                    else -> null
                }
            }
            is MessageContent.Notification -> listOf(content.message)
            is MessageContent.Error -> listOf(content.message)
            is MessageContent.Cancelled -> listOf(content.message)
            is MessageContent.Filter -> listOf(content.message)
            is MessageContent.Html -> listOf(content.html)
            is MessageContent.ContinueConfirmation -> content.messageParts.mapNotNull { part ->
                when (part) {
                    is MessageContent.MessagePart.Text -> part.text
                    is MessageContent.MessagePart.ActionLink -> part.text
                    else -> null
                }
            }
            is MessageContent.Hide -> extractTextFromMessageContent(content.content)
            else -> emptyList()
        }
    }

    private fun listFromCompositeModelCatalog(): List<AvailableModel> {
        return try {
            val modelService = ApplicationManager.getApplication().getService(CompositeModelService::class.java) ?: return emptyList()
            refreshModels(modelService)
            var models = mapCatalogModels(modelService)
            if (models.isEmpty()) {
                repeat(6) {
                    Thread.sleep(100)
                    models = mapCatalogModels(modelService)
                    if (models.isNotEmpty()) return@repeat
                }
            }
            models
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mapCatalogModels(modelService: CompositeModelService): List<AvailableModel> {
        return modelService.models.unscoped.value
            .map { model ->
                val provider = model.providerName?.takeIf { it.isNotBlank() }
                val readableName = if (provider != null) "${model.modelName} ($provider)" else model.modelName
                // id is the technical codename (modelName), label is the readable name
                AvailableModel(id = model.modelName, label = readableName)
            }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    private fun listFromChatModes(project: Project): List<AvailableModel> {
        val chatModeService = project.getService(ChatModeService::class.java) ?: return emptyList()
        val modeModels = chatModeService.chatModes.value
            .mapNotNull { it.model?.takeIf { model -> model.isNotBlank() } }
            .distinct()
            .sortedBy { it.lowercase() }
        return modeModels.map { model -> AvailableModel(id = model, label = model) }
    }

    private fun selectPreferredMode(chatModeService: ChatModeService?): RpcChatMode? {
        val modes = chatModeService?.chatModes?.value.orEmpty()
        // Default to Agent mode for richer autonomous behavior; fallback to Ask.
        return modes.firstOrNull { it.isAgentKind() || it.id.equals(RpcChatMode.BUILT_IN_AGENT_ID, ignoreCase = true) }
            ?: modes.firstOrNull { it.isAskKind() || it.id.equals("Ask", ignoreCase = true) }
    }

    private fun trySetSelectedChatMode(project: Project, selectedMode: RpcChatMode) {
        try {
            val serviceClass = Class.forName("com.github.copilot.agent.chatMode.UserSelectedChatModeService")
            val service = project.getService(serviceClass) ?: return
            val setter = serviceClass.methods.firstOrNull { method ->
                method.name == "setSelectedChatMode" && method.parameterCount == 1
            } ?: return
            setter.invoke(service, selectedMode)
        } catch (_: Throwable) {
            // Older/newer Copilot builds may not expose this service or setter.
        }
    }

    private fun refreshModels(modelService: CompositeModelService) {
        try {
            modelService.refreshModels()
        } catch (_: Throwable) {
            // Keep best-effort behavior if refresh APIs change across Copilot builds.
        }
    }

    private fun findModel(modelService: CompositeModelService, modelName: String, providerName: String?): CopilotModel? {
        return modelService.models.unscoped.value.firstOrNull { model ->
            val sameName = model.modelName.equals(modelName, ignoreCase = true)
            val sameProvider = providerName.isNullOrBlank() ||
                model.providerName.equals(providerName, ignoreCase = true)
            sameName && sameProvider
        }
    }
}
