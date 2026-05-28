package com.aibridge.server

import com.aibridge.bridge.AiProviderBridge
import com.aibridge.settings.AiBridgeSettings

/**
 * Returns model metadata for `/v1/models`.
 *
 * Uses discovered provider models when possible and falls back to a stable
 * default list when discovery is unavailable.
 */
internal class GatewayModelCatalog(
    private val bridgeProvider: () -> AiProviderBridge,
    private val settingsProvider: () -> AiBridgeSettings,
    private val projectResolver: GatewayProjectResolver,
    private val log: (String) -> Unit
) {
    private var lastLogMessage: String? = null

    suspend fun listModels(testProvider: (() -> List<ModelInfo>)?): List<ModelInfo> {
        if (testProvider != null) return testProvider()

        val settings = settingsProvider()
        val bridge = bridgeProvider()
        val fallback = buildFallbackModels()
        val project = projectResolver.selectDefaultProject()
        if (project == null) {
            val noProjectLog = "No open project for model discovery. Returning fallback model list (${fallback.size})."
            if (lastLogMessage != noProjectLog) {
                log(noProjectLog)
                lastLogMessage = noProjectLog
            }
            return fallback
        }

        val models = bridge.listAvailableModels(project)
        val logMessage = "Discovered ${models.size} models from ${settings.activeProvider.name}"
        if (lastLogMessage != logMessage) {
            log(logMessage)
            lastLogMessage = logMessage
        }

        val discovered = models.map { model ->
            ModelInfo(
                id = model.id,
                owned_by = settings.activeProvider.name.lowercase(),
                created = System.currentTimeMillis() / 1000,
                label = model.label
            )
        }
        return if (discovered.isNotEmpty()) discovered else fallback
    }

    private fun buildFallbackModels(): List<ModelInfo> {
        val settings = settingsProvider()
        val ids = linkedSetOf<String>()
        settings.defaultModel.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        
        ids.add("gpt-4o")
        ids.add("claude-3.5-sonnet")

        return ids.map { id -> ModelInfo(id = id, owned_by = settings.activeProvider.name.lowercase()) }
    }
}
