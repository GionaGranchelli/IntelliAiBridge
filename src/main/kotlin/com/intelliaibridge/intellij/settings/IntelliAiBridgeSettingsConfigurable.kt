package com.intelliaibridge.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intelliaibridge.intellij.copilot.CopilotBridge
import com.intelliaibridge.intellij.server.IntelliAiBridgeGateway
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * IntelliJ Settings UI for configuring IntelliAiBridge gateway behavior.
 */
class IntelliAiBridgeSettingsConfigurable : Configurable {
    /** UI option representing a model identifier and display label. */
    data class ModelOption(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private val settings = IntelliAiBridgeSettings.instance
    private val copilotBridge = CopilotBridge()
    private val envKeyActive = settings.isEnvApiKeyConfigured
    private var hostText = settings.host
    private var portValue = settings.port
    private var apiKeyInput: JPasswordField? = null
    private var apiKeyDirty = false
    private var clearStoredApiKeyValue = false
    private var autoStartValue = settings.autoStart
    private var maxConcurrentRequestsValue = settings.maxConcurrentRequests
    private var rateLimitPerMinuteValue = settings.rateLimitPerMinute
    
    // New parity settings
    private var corsAllowedOriginsText = settings.corsAllowedOrigins
    private var defaultModelIdValue = settings.defaultModel
    private var modelOptionsCombo: JComboBox<ModelOption>? = null
    private var defaultSystemPromptText = settings.defaultSystemPrompt
    private var requestTimeoutSecondsValue = settings.requestTimeoutSeconds
    private var enableLoggingValue = settings.enableLogging

    /** Builds the settings panel and initializes model list data. */
    override fun createComponent(): JComponent {
        val component = panel {
            group("Server Settings") {
                row("Host:") {
                    textField().bindText({ hostText }, { hostText = it })
                }
                row("Port:") {
                    intTextField(1024..65535).bindIntText({ portValue }, { portValue = it })
                }
                row("API Key:") {
                    val apiKeyFieldCell = passwordField()
                    apiKeyInput = apiKeyFieldCell.component
                    apiKeyFieldCell.component.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) {
                            apiKeyDirty = true
                        }

                        override fun removeUpdate(e: DocumentEvent?) {
                            apiKeyDirty = true
                        }

                        override fun changedUpdate(e: DocumentEvent?) {
                            apiKeyDirty = true
                        }
                    })
                    apiKeyFieldCell.comment(
                        if (envKeyActive) {
                            "INTELLIAIBRIDGE_API_KEY is set and currently overrides Password Safe. This field updates fallback key only."
                        } else {
                            "Stored in IntelliJ Password Safe. Leave blank to keep current stored key."
                        }
                    )
                }
                row {
                    checkBox("Clear stored API key from Password Safe")
                        .bindSelected({ clearStoredApiKeyValue }, { clearStoredApiKeyValue = it })
                }
                row {
                    checkBox("Automatically start server on launch")
                        .bindSelected({ autoStartValue }, { autoStartValue = it })
                }
            }
            group("Defaults") {
                row("Default Model:") {
                    val combo = JComboBox<ModelOption>()
                    modelOptionsCombo = combo
                    combo.addActionListener {
                        defaultModelIdValue = selectedModelId()
                    }
                    cell(combo)
                        .comment("Used when request `model` is not specified")
                    button("Refresh") {
                        refreshModelOptionsAsync()
                    }
                }
                row("Default System Prompt:") {
                    textArea().bindText({ defaultSystemPromptText }, { defaultSystemPromptText = it })
                        .comment("Injected if no system message is present in request")
                }
            }
            group("Limits") {
                row("Max Concurrent Requests:") {
                    intTextField(1..100).bindIntText({ maxConcurrentRequestsValue }, { maxConcurrentRequestsValue = it })
                }
                row("Rate Limit per Minute:") {
                    intTextField(1..1000).bindIntText({ rateLimitPerMinuteValue }, { rateLimitPerMinuteValue = it })
                }
                row("Request Timeout (Seconds):") {
                    intTextField(10..3600).bindIntText({ requestTimeoutSecondsValue }, { requestTimeoutSecondsValue = it })
                }
            }
            group("Advanced") {
                row("CORS Allowed Origins:") {
                    textField().bindText({ corsAllowedOriginsText }, { corsAllowedOriginsText = it })
                        .comment("Comma-separated list (e.g., http://localhost,http://127.0.0.1)")
                }
                row {
                    checkBox("Enable Verbose Logging")
                        .bindSelected({ enableLoggingValue }, { enableLoggingValue = it })
                }
            }
        }
        populateInitialModelOptions()
        refreshModelOptionsAsync()
        return component
    }

    /** Returns whether the current form values differ from persisted settings. */
    override fun isModified(): Boolean {
        return hostText != settings.host ||
                portValue != settings.port ||
                apiKeyDirty ||
                clearStoredApiKeyValue ||
                autoStartValue != settings.autoStart ||
                maxConcurrentRequestsValue != settings.maxConcurrentRequests ||
                rateLimitPerMinuteValue != settings.rateLimitPerMinute ||
                corsAllowedOriginsText != settings.corsAllowedOrigins ||
                selectedModelId() != settings.defaultModel ||
                defaultSystemPromptText != settings.defaultSystemPrompt ||
                requestTimeoutSecondsValue != settings.requestTimeoutSeconds ||
                enableLoggingValue != settings.enableLogging
    }

    /**
     * Persists settings changes and applies server restart semantics so the
     * gateway reflects updated runtime configuration.
     */
    override fun apply() {
        settings.host = hostText
        settings.port = portValue
        val typedApiKey = apiKeyInput?.password?.concatToString().orEmpty()
        when {
            clearStoredApiKeyValue -> settings.setStoredApiKey("")
            typedApiKey.isNotBlank() -> settings.setStoredApiKey(typedApiKey)
        }
        settings.autoStart = autoStartValue
        settings.maxConcurrentRequests = maxConcurrentRequestsValue
        settings.rateLimitPerMinute = rateLimitPerMinuteValue
        settings.corsAllowedOrigins = corsAllowedOriginsText
        settings.defaultModel = selectedModelId()
        settings.defaultSystemPrompt = defaultSystemPromptText
        settings.requestTimeoutSeconds = requestTimeoutSecondsValue
        settings.enableLogging = enableLoggingValue

        val gateway = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(IntelliAiBridgeGateway::class.java)
        gateway.stop()
        if (settings.autoStart) {
            gateway.start()
        }

        apiKeyDirty = false
        apiKeyInput?.text = ""
        clearStoredApiKeyValue = false
    }

    /** Display name shown in IntelliJ Settings tree. */
    override fun getDisplayName(): String = "IntelliAiBridge"

    /** Restores UI state from persisted settings. */
    override fun reset() {
        hostText = settings.host
        portValue = settings.port
        autoStartValue = settings.autoStart
        maxConcurrentRequestsValue = settings.maxConcurrentRequests
        rateLimitPerMinuteValue = settings.rateLimitPerMinute
        corsAllowedOriginsText = settings.corsAllowedOrigins
        defaultSystemPromptText = settings.defaultSystemPrompt
        requestTimeoutSecondsValue = settings.requestTimeoutSeconds
        enableLoggingValue = settings.enableLogging
        defaultModelIdValue = settings.defaultModel
        clearStoredApiKeyValue = false
        apiKeyDirty = false
        apiKeyInput?.text = ""
        populateInitialModelOptions()
        refreshModelOptionsAsync()
    }

    /** Returns currently selected model id from UI combo box. */
    private fun selectedModelId(): String {
        val selected = modelOptionsCombo?.selectedItem as? ModelOption
        return selected?.id ?: defaultModelIdValue
    }

    /** Populates model combo with persisted/default options before async refresh. */
    private fun populateInitialModelOptions() {
        val combo = modelOptionsCombo ?: return
        val options = mutableListOf(ModelOption("", "Copilot default"))
        if (defaultModelIdValue.isNotBlank()) {
            options.add(ModelOption(defaultModelIdValue, "$defaultModelIdValue (saved)"))
        }
        setModelOptions(combo, options, defaultModelIdValue)
    }

    /** Refreshes model options from Copilot in a background thread. */
    private fun refreshModelOptionsAsync() {
        val combo = modelOptionsCombo ?: return
        combo.isEnabled = false
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val project = ProjectManager.getInstance().openProjects
                .sortedWith(compareBy<Project>({ it.basePath ?: "" }, { it.name }))
                .firstOrNull()

            val discovered = project?.let { copilotBridge.listAvailableModels(it) }.orEmpty()
            val options = mutableListOf(ModelOption("", "Copilot default"))
            discovered.forEach { model ->
                options.add(ModelOption(model.id, model.label))
            }
            if (defaultModelIdValue.isNotBlank() && options.none { it.id == defaultModelIdValue }) {
                options.add(ModelOption(defaultModelIdValue, "$defaultModelIdValue (saved)"))
            }

            app.invokeLater {
                val currentCombo = modelOptionsCombo ?: return@invokeLater
                setModelOptions(currentCombo, options, defaultModelIdValue)
                currentCombo.isEnabled = true
            }
        }
    }

    /** Replaces model combo options while preserving deterministic selection. */
    private fun setModelOptions(combo: JComboBox<ModelOption>, options: List<ModelOption>, selectedId: String) {
        val unique = LinkedHashMap<String, ModelOption>()
        options.forEach { option ->
            unique.putIfAbsent(option.id, option)
        }
        val ordered = unique.values.toList()
        combo.model = DefaultComboBoxModel(ordered.toTypedArray())
        val selected = ordered.firstOrNull { it.id == selectedId } ?: ordered.firstOrNull()
        combo.selectedItem = selected
        defaultModelIdValue = selected?.id.orEmpty()
    }
}
