package com.intelliaibridge.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent application settings for the IntelliAiBridge gateway.
 *
 * Sensitive API key material is stored in [IntelliAiBridgeSecretStore] instead of
 * plain-text XML state.
 */
@State(
    name = "com.intelliaibridge.intellij.settings.IntelliAiBridgeSettings",
    storages = [Storage("IntelliAiBridge.xml")]
)
@Service(Service.Level.APP)
class IntelliAiBridgeSettings : PersistentStateComponent<IntelliAiBridgeSettings> {
    /** Indicates which source currently provides the effective API key. */
    enum class ApiKeySource {
        ENVIRONMENT,
        PASSWORD_SAFE,
        NONE
    }

    var host: String = "127.0.0.1"
    var port: Int = 3040
    // Legacy plaintext key field kept only for migration from existing state.
    var apiKey: String = ""
    var autoStart: Boolean = true
    var maxConcurrentRequests: Int = 4
    var rateLimitPerMinute: Int = 60
    
    // New parity settings
    var corsAllowedOrigins: String = "http://localhost,http://127.0.0.1,http://[::1]"
    var defaultModel: String = ""
    var defaultSystemPrompt: String = ""
    var requestTimeoutSeconds: Int = 300
    var enableLogging: Boolean = true

    override fun getState(): IntelliAiBridgeSettings = this

    /**
     * Loads persisted state and migrates any legacy plaintext API key value
     * into Password Safe.
     */
    override fun loadState(state: IntelliAiBridgeSettings) {
        val legacyApiKey = state.apiKey
        XmlSerializerUtil.copyBean(state, this)
        if (legacyApiKey.isNotBlank() && IntelliAiBridgeSecretStore.getApiKey().isBlank()) {
            IntelliAiBridgeSecretStore.setApiKey(legacyApiKey)
        }
        // Prevent future plaintext persistence after migration.
        apiKey = ""
    }

    /** Returns API key currently stored in IntelliJ Password Safe. */
    fun getStoredApiKey(): String = IntelliAiBridgeSecretStore.getApiKey()

    /** Persists API key in Password Safe and clears plaintext compatibility field. */
    fun setStoredApiKey(apiKey: String) {
        IntelliAiBridgeSecretStore.setApiKey(apiKey)
        // Keep persisted field empty to avoid plaintext storage in settings XML.
        this.apiKey = ""
    }

    /** Indicates whether `INTELLIAIBRIDGE_API_KEY` is present in process environment. */
    val isEnvApiKeyConfigured: Boolean
        get() = !System.getenv("INTELLIAIBRIDGE_API_KEY").isNullOrBlank()

    /** Describes which source currently supplies the effective API key. */
    val apiKeySource: ApiKeySource
        get() {
            val envApiKey = System.getenv("INTELLIAIBRIDGE_API_KEY")
            if (!envApiKey.isNullOrBlank()) return ApiKeySource.ENVIRONMENT
            if (getStoredApiKey().isNotBlank()) return ApiKeySource.PASSWORD_SAFE
            return ApiKeySource.NONE
        }

    /** Returns the key used for request authentication. Environment value wins. */
    val effectiveApiKey: String
        get() = System.getenv("INTELLIAIBRIDGE_API_KEY")?.takeIf { it.isNotBlank() } ?: getStoredApiKey()

    companion object {
        /** Convenience accessor for application-level settings service. */
        val instance: IntelliAiBridgeSettings
            get() = ApplicationManager.getApplication().getService(IntelliAiBridgeSettings::class.java)
    }
}
