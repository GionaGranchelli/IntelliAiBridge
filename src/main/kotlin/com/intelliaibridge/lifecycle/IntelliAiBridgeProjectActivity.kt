package com.intelliaibridge.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intelliaibridge.server.IntelliAiBridgeGateway
import com.intelliaibridge.settings.IntelliAiBridgeSettings

/**
 * Project startup hook that auto-starts IntelliAiBridge when enabled in settings.
 */
class IntelliAiBridgeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = IntelliAiBridgeSettings.instance
        if (settings.autoStart) {
            val gateway = ApplicationManager.getApplication().getService(IntelliAiBridgeGateway::class.java)
            gateway.start()
        }
    }
}
