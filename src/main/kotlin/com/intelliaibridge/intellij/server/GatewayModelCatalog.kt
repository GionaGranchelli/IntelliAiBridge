package com.intelliaibridge.intellij.server

import com.intelliaibridge.intellij.copilot.CopilotBridge
import com.intelliaibridge.intellij.settings.IntelliAiBridgeSettings

/**
 * Returns model metadata for `/v1/models`.
 *
 * Uses discovered Copilot models when possible and falls back to a stable
 * default list when discovery is unavailable.
 */
internal class GatewayModelCatalog(
    private val copilotBridge: CopilotBridge,
    private val settingsProvider: () -> IntelliAiBridgeSettings,
    private val projectResolver: GatewayProjectResolver,
    private val log: (String) -> Unit
) {
    fun listModels(testProvider: (() -> List<ModelInfo>)?): List<ModelInfo> {
        if (testProvider != null) return testProvider()

        val fallback = buildFallbackModels()
        val project = projectResolver.selectDefaultProject()
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
        val settings = settingsProvider()
        val ids = linkedSetOf<String>()
        settings.defaultModel.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        ids.add("gpt-4o")
        ids.add("claude-3.5-sonnet")
        return ids.map { id -> ModelInfo(id = id, owned_by = "github-copilot") }
    }
}
