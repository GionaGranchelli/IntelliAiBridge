package com.aibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.aibridge.bridge.AiProviderBridge
import com.aibridge.copilot.CopilotBridge
import com.aibridge.settings.AiBridgeSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application-level gateway that exposes AI providers as a local
 * OpenAI-compatible HTTP service.
 *
 * This service owns server lifecycle, request validation/authentication,
 * throttling, and endpoint routing.
 */
@Service(Service.Level.APP)
class AiBridgeGateway : AutoCloseable {
    private val LOG = Logger.getInstance(AiBridgeGateway::class.java)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val copilotBridge = CopilotBridge()
    private val jsonMapper = jacksonObjectMapper()
    private val lifecycleLock = Any()
    @Volatile private var starting = false
    @Volatile private var activeApiKey: String = ""
    @Volatile private var settingsOverride: AiBridgeSettings? = null
    @Volatile private var apiKeyOverride: String? = null
    @Volatile
    private var chatCompletionHandlerOverride: (suspend (ApplicationCall, ChatCompletionRequest) -> Unit)? = null
    @Volatile private var modelListProviderOverride: (() -> List<ModelInfo>)? = null

    private val activeRequests = AtomicInteger(0)
    private val rateBucket = ConcurrentLinkedQueue<Long>()
    private val rateLimitLock = Any()
    private val requestSlotKey = AttributeKey<Boolean>("aibridge.requestSlotAcquired")
    private val requestIdKey = AttributeKey<String>("aibridge.requestId")

    private val stats = object {
        val totalRequests = AtomicInteger(0)
        val startTime = System.currentTimeMillis()
    }

    private fun activeBridge(): AiProviderBridge {
        return copilotBridge
    }

    private val projectResolver by lazy { GatewayProjectResolver(::log) }
    private val xmlToolCallParser by lazy { GatewayXmlToolCallParser() }
    private val modelCatalog by lazy {
        GatewayModelCatalog(::activeBridge, ::currentSettings, projectResolver, ::log)
    }
    private val chatCompletions by lazy {
        GatewayChatCompletions(
            bridgeProvider = ::activeBridge,
            settingsProvider = ::currentSettings,
            projectResolver = projectResolver,
            buildPrompt = ::buildPrompt,
            parseXmlToolCalls = ::parseXmlToolCalls,
            log = ::log
        )
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
        println(formatted) // Always print to stdout for visibility in runIde
        if (currentSettings().enableLogging) {
            LOG.info(formatted)
        }
        logListeners.forEach { it.onLog(formatted) }
    }

    private fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AiBridge")
                ?.createNotification("AiBridge", message, type)
                ?.notify(null)
        }
    }

    internal fun setSettingsForTests(settings: AiBridgeSettings?) {
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

    private fun currentSettings(): AiBridgeSettings {
        return settingsOverride ?: AiBridgeSettings.instance
    }

    private var lastStartError: String? = null

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
        log("AiBridge Server start requested")
        synchronized(lifecycleLock) {
            if (server != null) {
                log("AiBridge Server is already running")
                return
            }
            if (starting) {
                log("AiBridge Server is already starting")
                return
            }
            starting = true
        }

        try {
            val settings = currentSettings()
            val effectiveApiKey = apiKeyOverride ?: settings.effectiveApiKey
            log("AiBridge Checking API key... (length=${effectiveApiKey.length})")
            if (effectiveApiKey.isBlank()) {
                val error = "AiBridge Server not started: API key missing. Set AIBRIDGE_API_KEY or configure AiBridge API Key in settings."
                log(error)
                notify(error, NotificationType.WARNING)
                return
            }
            lastStartError = null
            val keySource = when {
                apiKeyOverride != null -> "test override"
                else -> when (settings.apiKeySource) {
                    AiBridgeSettings.ApiKeySource.ENVIRONMENT -> "AIBRIDGE_API_KEY environment variable"
                    AiBridgeSettings.ApiKeySource.PASSWORD_SAFE -> "IDE Password Safe"
                    AiBridgeSettings.ApiKeySource.NONE -> "none"
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
                        call.respond(HttpStatusCode.Unauthorized, OpenAiErrorResponse(OpenAiError("Invalid API Key", "invalid_request_error", code = "invalid_api_key")))
                        finish()
                        return@intercept
                    }

                    if (!checkRateLimit(settings.rateLimitPerMinute)) {
                        log("[${requestId(call)}] Rate limit exceeded")
                        call.respond(HttpStatusCode.TooManyRequests, OpenAiErrorResponse(OpenAiError("Rate limit exceeded", "rate_limit_error")))
                        finish()
                        return@intercept
                    }

                    if (isCompletionEndpoint(call)) {
                        if (!tryAcquireRequestSlot(settings.maxConcurrentRequests)) {
                            log("[${requestId(call)}] Server at capacity")
                            call.respond(HttpStatusCode.ServiceUnavailable, OpenAiErrorResponse(OpenAiError("Server at capacity", "server_error")))
                            finish()
                            return@intercept
                        }
                        call.attributes.put(requestSlotKey, true)
                    }
                }

                routing {
                    get("/health") {
                        call.respond(mapOf("status" to "ok", "platform" to "ide", "copilot" to "enabled"))
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
                            call.respond(HttpStatusCode.NotFound, OpenAiErrorResponse(OpenAiError("Model not found", "invalid_request_error", code = "model_not_found")))
                        }
                    }
                    post("/v1/chat/completions") {
                        try {
                            val request = receiveJsonBody<ChatCompletionRequest>(call, "/v1/chat/completions") ?: return@post
                            stats.totalRequests.incrementAndGet()
                            log("[${requestId(call)}] POST /v1/chat/completions - model: ${request.model ?: "default"}")
                            executeChatCompletion(call, request, settings)
                        } finally {
                            releaseRequestSlot(call)
                        }
                    }
                    post("/v1/completions") {
                        try {
                            val request = receiveJsonBody<CompletionsRequest>(call, "/v1/completions") ?: return@post
                            stats.totalRequests.incrementAndGet()
                            log("[${requestId(call)}] POST /v1/completions (legacy) - model: ${request.model ?: "default"}")
                            executeChatCompletion(call, request.toChatCompletionRequest(), settings)
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
            log("AiBridge Active Provider: ${settings.activeProvider}")
            log("AiBridge API key source: $keySource")
            val startMsg = "AiBridge Server started on http://${settings.host}:${settings.port}"
            log(startMsg)
            notify(startMsg)
        } catch (e: Exception) {
            val errorMsg = if (e.cause is java.net.BindException || e is java.net.BindException) {
                "AiBridge failed to start: Port already in use. Please change the port in settings."
            } else {
                "AiBridge Server failed to start: ${e.message}"
            }
            log(errorMsg)
            notify(errorMsg, NotificationType.ERROR)
            e.printStackTrace()
        } finally {
            synchronized(lifecycleLock) {
                starting = false
            }
        }
    }

    private suspend fun executeChatCompletion(
        call: ApplicationCall,
        request: ChatCompletionRequest,
        settings: AiBridgeSettings
    ) {
        try {
            withTimeout(settings.requestTimeoutSeconds * 1000L) {
                val testHandler = chatCompletionHandlerOverride
                if (testHandler != null) testHandler(call, request) else handleChatCompletion(call, request)
            }
        } catch (e: TimeoutCancellationException) {
            log("[${requestId(call)}] Request timeout")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.GatewayTimeout, OpenAiErrorResponse(OpenAiError("Request timeout", "server_error", code = "request_timeout")))
            }
        } catch (e: Exception) {
            log("[${requestId(call)}] Error handling request: ${e.message}")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.InternalServerError, OpenAiErrorResponse(OpenAiError(e.message ?: "Internal error", "server_error")))
            }
        }
    }

    private fun CompletionsRequest.toChatCompletionRequest(): ChatCompletionRequest {
        val prompt = when (val value = prompt) {
            is String -> value
            is List<*> -> value.filterIsInstance<String>().joinToString("\n")
            else -> ""
        }

        return ChatCompletionRequest(
            model = model,
            messages = listOf(ChatMessage("user", prompt)),
            stream = stream,
            max_tokens = max_tokens,
            temperature = temperature
        )
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
                call.respond(HttpStatusCode.BadRequest, OpenAiErrorResponse(OpenAiError("Unable to read request body", "invalid_request_error")))
            }
            return null
        }

        if (body.isBlank()) {
            log("[$requestId] Empty request body for $endpoint")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadRequest, OpenAiErrorResponse(OpenAiError("Empty request body", "invalid_request_error")))
            }
            return null
        }

        return try {
            jsonMapper.readValue<T>(body)
        } catch (e: Exception) {
            val preview = body.replace("\n", " ").take(500)
            log("[$requestId] Invalid JSON body for $endpoint: ${e.message}. bodyPreview=$preview")
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadRequest, OpenAiErrorResponse(OpenAiError("Invalid JSON request body", "invalid_request_error")))
            }
            null
        }
    }

    private suspend fun handleChatCompletion(call: ApplicationCall, request: ChatCompletionRequest) {
        chatCompletions.handle(call, request)
    }

    private fun parseXmlToolCalls(text: String): ParsedXmlTools = xmlToolCallParser.parse(text)

    private fun buildPrompt(messages: List<ChatMessage>, defaultSystemPrompt: String): String {
        return GatewayPromptBuilder.build(messages, defaultSystemPrompt)
    }

    /** Stops the embedded HTTP server if currently active. */
    fun stop() {
        val toStop = synchronized(lifecycleLock) {
            val current = server
            server = null
            activeApiKey = ""
            current
        }
        if (toStop != null) {
            toStop.stop(1000, 1000)
            log("AiBridge Server stopped")
            notify("AiBridge Server stopped")
        }
    }

    /** Returns the list of available models. */
    fun listModels(): List<ModelInfo> {
        return modelCatalog.listModels(modelListProviderOverride)
    }

    /** Releases server and coroutine resources when the service is disposed. */
    override fun close() {
        stop()
        scope.cancel()
    }
}
