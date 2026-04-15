package com.intelliaibridge.intellij.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intelliaibridge.intellij.copilot.CopilotBridge
import com.intelliaibridge.intellij.settings.IntelliAiBridgeSettings
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import org.w3c.dom.Element
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Application-level gateway that exposes IntelliJ Copilot as a local
 * OpenAI-compatible HTTP service.
 *
 * This service owns server lifecycle, request validation/authentication,
 * throttling, and response adaptation.
 */
@Service(Service.Level.APP)
class IntelliAiBridgeGateway : AutoCloseable {
    private val LOG = Logger.getInstance(IntelliAiBridgeGateway::class.java)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val copilotBridge = CopilotBridge()
    private val jsonMapper = jacksonObjectMapper()
    private val lifecycleLock = Any()
    @Volatile private var starting = false
    @Volatile private var activeApiKey: String = ""
    @Volatile private var settingsOverride: IntelliAiBridgeSettings? = null
    @Volatile private var apiKeyOverride: String? = null
    @Volatile
    private var chatCompletionHandlerOverride: (suspend (ApplicationCall, ChatCompletionRequest) -> Unit)? = null
    @Volatile private var modelListProviderOverride: (() -> List<ModelInfo>)? = null

    private val activeRequests = AtomicInteger(0)
    private val rateBucket = ConcurrentLinkedQueue<Long>()
    private val rateLimitLock = Any()
    private val requestSlotKey = AttributeKey<Boolean>("intelliaibridge.requestSlotAcquired")
    private val requestIdKey = AttributeKey<String>("intelliaibridge.requestId")

    private val stats = object {
        val totalRequests = AtomicInteger(0)
        val startTime = System.currentTimeMillis()
    }

    /** Returns `true` when the embedded HTTP server is running. */
    fun isRunning(): Boolean = server != null

    /**
     * Returns lightweight runtime metrics for UI/status consumption.
     *
     * Keys:
     * - `totalRequests`
     * - `uptime` (milliseconds)
     * - `activeRequests`
     */
    fun getStats() = mapOf(
        "totalRequests" to stats.totalRequests.get(),
        "uptime" to (System.currentTimeMillis() - stats.startTime),
        "activeRequests" to activeRequests.get()
    )

    /** Listener interface used by UI components to consume gateway log lines. */
    interface LogListener {
        fun onLog(message: String)
    }

    private val logListeners = mutableListOf<LogListener>()
    /** Registers a [LogListener]. */
    fun addLogListener(listener: LogListener) = logListeners.add(listener)

    /** Unregisters a [LogListener]. */
    fun removeLogListener(listener: LogListener) = logListeners.remove(listener)

    private fun log(message: String) {
        val ts = java.time.LocalDateTime.now().toString()
        val formatted = "[$ts] $message"
        if (currentSettings().enableLogging) {
            LOG.info(formatted)
        }
        logListeners.forEach { it.onLog(formatted) }
    }

    internal fun setSettingsForTests(settings: IntelliAiBridgeSettings?) {
        settingsOverride = settings
    }

    internal fun setApiKeyForTests(apiKey: String?) {
        apiKeyOverride = apiKey
    }

    internal fun setChatCompletionHandlerForTests(handler: (suspend (ApplicationCall, ChatCompletionRequest) -> Unit)?) {
        chatCompletionHandlerOverride = handler
    }

    internal fun setModelListProviderForTests(provider: (() -> List<ModelInfo>)?) {
        modelListProviderOverride = provider
    }

    private fun currentSettings(): IntelliAiBridgeSettings {
        return settingsOverride ?: IntelliAiBridgeSettings.instance
    }

    /**
     * Starts the gateway server.
     *
     * Startup is moved to a background coroutine when invoked from the EDT.
     */
    fun start() {
        val app = runCatching { ApplicationManager.getApplication() }.getOrNull()
        if (app != null && app.isDispatchThread) {
            scope.launch { startInternal() }
        } else {
            startInternal()
        }
    }

    private fun startInternal() {
        synchronized(lifecycleLock) {
            if (server != null || starting) return
            starting = true
        }

        try {
            val settings = currentSettings()
            val effectiveApiKey = apiKeyOverride ?: settings.effectiveApiKey
            if (effectiveApiKey.isBlank()) {
                log("IntelliAiBridge Server not started: API key missing. Set INTELLIAIBRIDGE_API_KEY or configure IntelliAiBridge API Key in settings.")
                return
            }
            val keySource = when {
                apiKeyOverride != null -> "test override"
                else -> when (settings.apiKeySource) {
                    IntelliAiBridgeSettings.ApiKeySource.ENVIRONMENT -> "INTELLIAIBRIDGE_API_KEY environment variable"
                    IntelliAiBridgeSettings.ApiKeySource.PASSWORD_SAFE -> "IntelliJ Password Safe"
                    IntelliAiBridgeSettings.ApiKeySource.NONE -> "none"
                }
            }

            val createdServer = embeddedServer(Netty, port = settings.port, host = settings.host) {
            install(ContentNegotiation) {
                jackson()
            }
            install(CORS) {
                val allowed = settings.corsAllowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                allowed.forEach { origin ->
                    val host = origin.removePrefix("http://").removePrefix("https://")
                    if (!host.contains(":")) {
                        allowHost(host, listOf("http", "https"))
                    } else {
                        allowHost(host.split(":")[0], listOf("http", "https"))
                    }
                }
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
            }

            intercept(ApplicationCallPipeline.Plugins) {
                val requestId = UUID.randomUUID().toString().take(8)
                call.attributes.put(requestIdKey, requestId)
                val contentType = call.request.header(HttpHeaders.ContentType) ?: "<none>"
                val contentLength = call.request.header(HttpHeaders.ContentLength) ?: "<none>"
                val userAgent = call.request.header(HttpHeaders.UserAgent) ?: "<none>"
                log("[$requestId] Incoming request: ${call.request.httpMethod.value} ${call.request.uri} from ${call.request.local.remoteHost} ct=$contentType len=$contentLength ua=$userAgent")

                if (call.request.uri == "/health" || call.request.httpMethod == HttpMethod.Options) return@intercept

                val authHeader = call.request.header(HttpHeaders.Authorization)
                if (authHeader != "Bearer $activeApiKey") {
                    log("[${requestId(call)}] Unauthorized request from ${call.request.local.remoteHost}")
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API Key"))
                    finish()
                    return@intercept
                }

                if (!checkRateLimit(settings.rateLimitPerMinute)) {
                    log("[${requestId(call)}] Rate limit exceeded")
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                    finish()
                    return@intercept
                }

                if (isCompletionEndpoint(call)) {
                    if (!tryAcquireRequestSlot(settings.maxConcurrentRequests)) {
                        log("[${requestId(call)}] Server at capacity")
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Server at capacity"))
                        finish()
                        return@intercept
                    }
                    call.attributes.put(requestSlotKey, true)
                }
            }

            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok", "platform" to "intellij", "copilot" to "enabled"))
                }
                get("/v1/models") {
                    log("[${requestId(call)}] GET /v1/models")
                    call.respond(mapOf("object" to "list", "data" to listModels()))
                }
                get("/v1/models/{id}") {
                    val id = call.parameters["id"]
                    log("[${requestId(call)}] GET /v1/models/$id")
                    val model = listModels().find { it.id == id }
                    if (model != null) {
                        call.respond(model)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found"))
                    }
                }
                post("/v1/chat/completions") {
                    try {
                        val request = receiveJsonBody<ChatCompletionRequest>(call, "/v1/chat/completions") ?: return@post
                        stats.totalRequests.incrementAndGet()
                        log("[${requestId(call)}] POST /v1/chat/completions - model: ${request.model ?: "default"}")

                        try {
                            withTimeout(settings.requestTimeoutSeconds * 1000L) {
                                val testHandler = chatCompletionHandlerOverride
                                if (testHandler != null) testHandler(call, request) else handleChatCompletion(call, request)
                            }
                        } catch (e: TimeoutCancellationException) {
                            log("[${requestId(call)}] Request timeout")
                            if (!call.response.isCommitted) {
                                call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to "Request timeout"))
                            }
                        } catch (e: Exception) {
                            log("[${requestId(call)}] Error handling request: ${e.message}")
                            if (!call.response.isCommitted) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Internal error")))
                            }
                        }
                    } finally {
                        releaseRequestSlot(call)
                    }
                }
                post("/v1/completions") {
                    try {
                        val request = receiveJsonBody<CompletionsRequest>(call, "/v1/completions") ?: return@post
                        stats.totalRequests.incrementAndGet()
                        log("[${requestId(call)}] POST /v1/completions (legacy) - model: ${request.model ?: "default"}")

                        val prompt = when (val p = request.prompt) {
                            is String -> p
                            is List<*> -> p.filterIsInstance<String>().joinToString("\n")
                            else -> ""
                        }

                        val chatRequest = ChatCompletionRequest(
                            model = request.model,
                            messages = listOf(ChatMessage("user", prompt)),
                            stream = request.stream,
                            max_tokens = request.max_tokens,
                            temperature = request.temperature
                        )

                        try {
                            withTimeout(settings.requestTimeoutSeconds * 1000L) {
                                val testHandler = chatCompletionHandlerOverride
                                if (testHandler != null) testHandler(call, chatRequest) else handleChatCompletion(call, chatRequest)
                            }
                        } catch (e: TimeoutCancellationException) {
                            log("[${requestId(call)}] Request timeout")
                            if (!call.response.isCommitted) {
                                call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to "Request timeout"))
                            }
                        } catch (e: Exception) {
                            log("[${requestId(call)}] Error handling request: ${e.message}")
                            if (!call.response.isCommitted) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Internal error")))
                            }
                        }
                    } finally {
                        releaseRequestSlot(call)
                    }
                }
            }
            }

            createdServer.start(wait = false)
            synchronized(lifecycleLock) {
                server = createdServer
                activeApiKey = effectiveApiKey
            }
            log("IntelliAiBridge API key source: $keySource")
            log("IntelliAiBridge Server started on http://${settings.host}:${settings.port}")
        } finally {
            synchronized(lifecycleLock) {
                starting = false
            }
        }
    }

    private fun checkRateLimit(limit: Int): Boolean {
        synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val windowMs = 60_000L
            while (rateBucket.peek() != null && now - rateBucket.peek()!! > windowMs) {
                rateBucket.poll()
            }
            if (rateBucket.size >= limit) return false
            rateBucket.add(now)
            return true
        }
    }

    private fun isCompletionEndpoint(call: ApplicationCall): Boolean {
        return call.request.uri == "/v1/chat/completions" || call.request.uri == "/v1/completions"
    }

    private fun tryAcquireRequestSlot(maxConcurrentRequests: Int): Boolean {
        while (true) {
            val current = activeRequests.get()
            if (current >= maxConcurrentRequests) {
                return false
            }
            if (activeRequests.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    private fun releaseRequestSlot(call: ApplicationCall) {
        if (!call.attributes.contains(requestSlotKey)) {
            return
        }
        activeRequests.decrementAndGet()
    }

    private fun requestId(call: ApplicationCall): String {
        return if (call.attributes.contains(requestIdKey)) call.attributes[requestIdKey] else "-"
    }

    /**
     * Reads and deserializes JSON body, returning `null` and writing a
     * client-facing error response when body parsing fails.
     */
    private suspend inline fun <reified T> receiveJsonBody(call: ApplicationCall, endpoint: String): T? {
        val requestId = requestId(call)
        val body = try {
            call.receiveText()
        } catch (e: Exception) {
            log("[$requestId] Failed to read request body for $endpoint: ${e.message}")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unable to read request body"))
            }
            return null
        }

        if (body.isBlank()) {
            log("[$requestId] Empty request body for $endpoint")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty request body"))
            }
            return null
        }

        return try {
            jsonMapper.readValue<T>(body)
        } catch (e: Exception) {
            val preview = body.replace("\n", " ").take(500)
            log("[$requestId] Invalid JSON body for $endpoint: ${e.message}. bodyPreview=$preview")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON request body"))
            }
            null
        }
    }

    /**
     * Handles `/v1/chat/completions` for both streaming and non-streaming modes.
     */
    private suspend fun handleChatCompletion(call: ApplicationCall, request: ChatCompletionRequest) {
        val settings = currentSettings()
        val project = resolveProject(call)
        if (project == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No open project found"))
            return
        }

        val prompt = buildPrompt(
            messages = request.messages,
            defaultSystemPrompt = settings.defaultSystemPrompt
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
                val streamResult = CompletableDeferred<String>()
                var fullContent = ""

                copilotBridge.sendMessage(sessionHandle, prompt) { event ->
                    log("Event received: ${event.type}")
                    when (event) {
                        is CopilotBridge.Event.Progress -> {
                            if (event.reply != null) {
                                fullContent += event.reply
                            }
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
                            streamResult.complete(fullContent)
                        }
                        is CopilotBridge.Event.Error -> {
                            streamResult.completeExceptionally(Exception(event.message))
                        }
                        is CopilotBridge.Event.Other -> {
                        }
                    }
                }

                val completedText = streamResult.await()
                log("Copilot Completed. Total length: ${completedText.length}")
                val parsed = parseXmlToolCalls(completedText)

                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val requestId = "chatcmpl-${UUID.randomUUID()}"
                    val created = System.currentTimeMillis() / 1000
                    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

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
            } else {
                val result = CompletableDeferred<ChatCompletionResponse>()
                var fullContent = ""

                copilotBridge.sendMessage(sessionHandle, prompt) { event ->
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
                            val parsed = parseXmlToolCalls(fullContent)
                            val responseMessage = if (parsed.toolCalls.isNotEmpty()) {
                                ChatMessage("assistant", null, tool_calls = parsed.toolCalls)
                            } else {
                                ChatMessage("assistant", fullContent)
                            }

                            result.complete(
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
                        is CopilotBridge.Event.Error -> {
                            result.completeExceptionally(Exception(event.message))
                        }
                        is CopilotBridge.Event.Other -> {
                        }
                    }
                }

                call.respond(result.await())
            }
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Copilot unavailable")))
        } finally {
            if (sessionHandle != null) {
                copilotBridge.cleanupSession(sessionHandle, ::log)
            }
        }
    }

    /**
     * Extracts `<function_calls>` blocks from model text and converts them to
     * OpenAI-style tool calls while returning the remaining visible assistant text.
     */
    private fun parseXmlToolCalls(text: String): ParsedXmlTools {
        val toolCalls = mutableListOf<ToolCall>()
        val cleanedText = StringBuilder(text)
        val matches = mutableListOf<Pair<Int, Int>>()

        for ((start, end, block) in findFunctionCallBlocks(text)) {
            toolCalls.addAll(parseFunctionCallBlock(block))
            matches.add(Pair(start, end))
        }

        matches.reversed().forEach { (start, end) ->
            cleanedText.delete(start, end)
        }

        return ParsedXmlTools(cleanedText.toString().trim(), toolCalls)
    }

    /** Result container for XML tool-call parsing. */
    data class ParsedXmlTools(val cleanedText: String, val toolCalls: List<ToolCall>)

    private fun findFunctionCallBlocks(text: String): List<Triple<Int, Int, String>> {
        val blocks = mutableListOf<Triple<Int, Int, String>>()
        val startTag = "<function_calls>"
        val endTag = "</function_calls>"
        var cursor = 0

        while (true) {
            val start = text.indexOf(startTag, cursor)
            if (start < 0) {
                break
            }
            val endTagStart = text.indexOf(endTag, start + startTag.length)
            if (endTagStart < 0) {
                break
            }
            val end = endTagStart + endTag.length
            val inner = text.substring(start + startTag.length, endTagStart)
            blocks.add(Triple(start, end, inner))
            cursor = end
        }

        return blocks
    }

    private fun parseFunctionCallBlock(block: String): List<ToolCall> {
        val xml = "<function_calls>$block</function_calls>"
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

        return try {
            val documentBuilder = documentBuilderFactory.newDocumentBuilder()
            val document = documentBuilder.parse(InputSource(StringReader(xml)))
            val root = document.documentElement
            if (root == null || root.nodeName != "function_calls") {
                return emptyList()
            }

            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val parsed = mutableListOf<ToolCall>()
            val invokeNodes = root.getElementsByTagName("invoke")
            for (index in 0 until invokeNodes.length) {
                val invoke = invokeNodes.item(index) as? Element ?: continue
                val name = invoke.getAttribute("name")?.trim().orEmpty()
                if (name.isBlank()) continue

                val params = linkedMapOf<String, String>()
                val children = invoke.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i) as? Element ?: continue
                    if (child.tagName != "parameter") continue
                    val paramName = child.getAttribute("name")?.trim().orEmpty()
                    if (paramName.isBlank()) continue
                    params[paramName] = child.textContent ?: ""
                }

                parsed.add(
                    ToolCall(
                        id = "call_${UUID.randomUUID()}",
                        function = FunctionCall(name, mapper.writeValueAsString(params))
                    )
                )
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Flattens OpenAI chat messages into a single prompt accepted by Copilot
     * conversation APIs, optionally injecting a default system prompt.
     */
    private fun buildPrompt(messages: List<ChatMessage>, defaultSystemPrompt: String): String {
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

    /** Stops the embedded HTTP server if currently active. */
    fun stop() {
        val toStop = synchronized(lifecycleLock) {
            val current = server
            server = null
            activeApiKey = ""
            current
        }
        toStop?.stop(1000, 1000)
        log("IntelliAiBridge Server stopped")
    }

    /**
     * Returns model metadata for `/v1/models`.
     *
     * Uses discovered Copilot models when possible and falls back to a stable
     * default list when discovery is unavailable.
     */
    private fun listModels(): List<ModelInfo> {
        val testProvider = modelListProviderOverride
        if (testProvider != null) return testProvider()

        val fallback = buildFallbackModels()
        val project = selectDefaultProject()
        if (project == null) {
            log("No open project for model discovery. Returning fallback model list (${fallback.size}).")
            return fallback
        }

        val models = copilotBridge.listAvailableModels(project)
        log("Discovered ${models.size} models from Copilot")

        val discovered = models.map { model ->
            ModelInfo(
                id = model.id,
                owned_by = "github-copilot",
                created = System.currentTimeMillis() / 1000
            )
        }
        return if (discovered.isNotEmpty()) discovered else fallback
    }

    private fun buildFallbackModels(): List<ModelInfo> {
        val settings = currentSettings()
        val ids = linkedSetOf<String>()
        settings.defaultModel.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        ids.add("gpt-4o")
        ids.add("claude-3.5-sonnet")
        return ids.map { id -> ModelInfo(id = id, owned_by = "github-copilot") }
    }

    /** Releases server and coroutine resources when the service is disposed. */
    override fun close() {
        stop()
        scope.cancel()
    }

    /**
     * Selects project context for a request, honoring `X-IntelliAiBridge-Project`
     * when provided and falling back to deterministic default selection.
     */
    private fun resolveProject(call: ApplicationCall): Project? {
        val requested = call.request.header("X-IntelliAiBridge-Project")?.trim().orEmpty()
        if (requested.isNotBlank()) {
            val selected = findProjectBySelector(requested)
            if (selected != null) {
                return selected
            }
            log("Requested project '$requested' not found. Falling back to deterministic default project.")
        }
        return selectDefaultProject()
    }

    private fun selectDefaultProject(): Project? {
        return ProjectManager.getInstance().openProjects
            .sortedWith(compareBy<Project>({ it.basePath ?: "" }, { it.name }))
            .firstOrNull()
    }

    private fun findProjectBySelector(selector: String): Project? {
        val normalized = selector.lowercase()
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val projectName = project.name.lowercase()
            val basePath = project.basePath?.lowercase()
            projectName == normalized ||
                basePath == normalized ||
                basePath?.substringAfterLast('/') == normalized
        }
    }
}
