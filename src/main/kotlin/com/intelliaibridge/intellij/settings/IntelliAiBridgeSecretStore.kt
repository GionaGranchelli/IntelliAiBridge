package com.intelliaibridge.intellij.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Wrapper around IntelliJ Password Safe for IntelliAiBridge secrets.
 */
object IntelliAiBridgeSecretStore {
    private const val SERVICE = "IntelliAiBridge"
    private const val KEY = "api-key"
    private const val USER = "intelliaibridge"

    private val attributes = CredentialAttributes(generateServiceName(SERVICE, KEY))

    /** Reads stored IntelliAiBridge API key, or empty string when unset. */
    fun getApiKey(): String {
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    }

    /** Stores or clears IntelliAiBridge API key in Password Safe. */
    fun setApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(USER, apiKey))
        }
    }
}
