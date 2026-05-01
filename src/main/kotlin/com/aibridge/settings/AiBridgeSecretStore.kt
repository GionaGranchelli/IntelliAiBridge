package com.aibridge.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Wrapper around IDE Password Safe for AiBridge secrets.
 */
object AiBridgeSecretStore {
    private const val SERVICE = "AiBridge"
    private const val KEY = "api-key"
    private const val USER = "aibridge"

    private val attributes = CredentialAttributes(generateServiceName(SERVICE, KEY))

    /** Reads stored AiBridge API key, or empty string when unset. */
    fun getApiKey(): String {
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    }

    /** Stores or clears AiBridge API key in Password Safe. */
    fun setApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(USER, apiKey))
        }
    }
}
