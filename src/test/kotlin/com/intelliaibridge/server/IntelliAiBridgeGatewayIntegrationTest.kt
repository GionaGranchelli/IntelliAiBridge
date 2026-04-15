package com.intelliaibridge.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intelliaibridge.settings.IntelliAiBridgeSettings
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class IntelliAiBridgeGatewayIntegrationTest {
    private val mapper = jacksonObjectMapper()
    private val http = HttpClient.newHttpClient()
    private lateinit var gateway: IntelliAiBridgeGateway
    private lateinit var settings: IntelliAiBridgeSettings
    private lateinit var baseUrl: String
    private val apiKey = "test-intelliaibridge-key"

    @BeforeEach
    fun setUp() {
        settings = IntelliAiBridgeSettings().apply {
            host = "127.0.0.1"
            port = findFreePort()
            enableLogging = false
            autoStart = false
            requestTimeoutSeconds = 5
            maxConcurrentRequests = 4
            rateLimitPerMinute = 60
        }
        baseUrl = "http://${settings.host}:${settings.port}"

        gateway = IntelliAiBridgeGateway()
        gateway.setSettingsForTests(settings)
        gateway.setApiKeyForTests(apiKey)
        gateway.setModelListProviderForTests {
            listOf(ModelInfo(id = "gpt-4o", owned_by = "github-copilot"))
        }
        gateway.setChatCompletionHandlerForTests { call, request ->
            if (request.stream) {
                val requestId = "chatcmpl-${UUID.randomUUID()}"
                val created = System.currentTimeMillis() / 1000
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val firstChunk = ChatCompletionChunkResponse(
                        id = requestId,
                        created = created,
                        model = request.model ?: "gpt-4o",
                        choices = listOf(ChatChunkChoice(index = 0, delta = ChatChunkDelta(content = "hello"), finish_reason = null))
                    )
                    val finalChunk = ChatCompletionChunkResponse(
                        id = requestId,
                        created = created,
                        model = request.model ?: "gpt-4o",
                        choices = listOf(ChatChunkChoice(index = 0, delta = ChatChunkDelta(), finish_reason = "stop"))
                    )
                    write("data: ${mapper.writeValueAsString(firstChunk)}\n\n")
                    write("data: ${mapper.writeValueAsString(finalChunk)}\n\n")
                    write("data: [DONE]\n\n")
                    flush()
                }
            } else {
                call.respond(
                    ChatCompletionResponse(
                        id = "chatcmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = request.model ?: "gpt-4o",
                        choices = listOf(
                            ChatChoice(
                                index = 0,
                                message = ChatMessage("assistant", "hello"),
                                finish_reason = "stop"
                            )
                        )
                    )
                )
            }
        }

        gateway.start()
        waitForServer()
    }

    @AfterEach
    fun tearDown() {
        gateway.stop()
    }

    @Test
    fun `health endpoint responds without auth`() {
        val response = sendGet("$baseUrl/health")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertEquals("ok", json["status"].asText())
        assertEquals("intellij", json["platform"].asText())
    }

    @Test
    fun `protected endpoint requires bearer token`() {
        val response = sendGet("$baseUrl/v1/models")
        assertEquals(401, response.statusCode())
    }

    @Test
    fun `chat completions non-stream returns openai response shape`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":false}"""
                )
            )
            .timeout(Duration.ofSeconds(5))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        val json = mapper.readTree(response.body())
        assertEquals("chat.completion", json["object"].asText())
        assertEquals("assistant", json["choices"][0]["message"]["role"].asText())
        assertEquals("hello", json["choices"][0]["message"]["content"].asText())
    }

    @Test
    fun `chat completions stream returns sse chunks and done marker`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":true}"""
                )
            )
            .timeout(Duration.ofSeconds(5))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("data: {"))
        assertTrue(body.contains("\"object\":\"chat.completion.chunk\""))
        assertTrue(body.contains("data: [DONE]"))
    }

    @Test
    fun `rate limit returns 429 after capacity reached within one minute`() {
        settings.rateLimitPerMinute = 1
        gateway.stop()
        gateway.start()
        waitForServer()

        val first = sendAuthorizedGet("$baseUrl/v1/models")
        val second = sendAuthorizedGet("$baseUrl/v1/models")

        assertEquals(200, first.statusCode())
        assertEquals(429, second.statusCode())
    }

    @Test
    fun `concurrency limit returns 503 for second simultaneous completion`() {
        settings.maxConcurrentRequests = 1
        gateway.setChatCompletionHandlerForTests { call, request ->
            if (request.stream) {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    Thread.sleep(700)
                    write("data: [DONE]\n\n")
                    flush()
                }
            } else {
                Thread.sleep(700)
                call.respond(
                    ChatCompletionResponse(
                        id = "chatcmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = request.model ?: "gpt-4o",
                        choices = listOf(ChatChoice(0, ChatMessage("assistant", "slow")))
                    )
                )
            }
        }

        gateway.stop()
        gateway.start()
        waitForServer()

        lateinit var first: HttpResponse<String>
        lateinit var second: HttpResponse<String>

        val t1 = thread {
            first = sendChatRequest(stream = false)
        }
        Thread.sleep(100)
        val t2 = thread {
            second = sendChatRequest(stream = false)
        }

        t1.join()
        t2.join()

        val statuses = listOf(first.statusCode(), second.statusCode()).sorted()
        assertEquals(listOf(200, 503), statuses)
    }

    @Test
    fun `models endpoint returns openai list shape`() {
        val response = sendAuthorizedGet("$baseUrl/v1/models")
        assertEquals(200, response.statusCode())
        val json = mapper.readTree(response.body())
        assertEquals("list", json["object"].asText())
        assertTrue(json.has("data"))
        assertTrue(json["data"].isArray)
    }

    private fun sendChatRequest(stream: Boolean): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":$stream}"""
                )
            )
            .timeout(Duration.ofSeconds(5))
            .build()

        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun sendAuthorizedGet(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun waitForServer() {
        repeat(20) {
            val response = runCatching { sendGet("$baseUrl/health") }.getOrNull()
            if (response?.statusCode() == 200) return
            TimeUnit.MILLISECONDS.sleep(100)
        }
        throw IllegalStateException("Server did not become healthy in time")
    }

    private fun sendGet(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
}
