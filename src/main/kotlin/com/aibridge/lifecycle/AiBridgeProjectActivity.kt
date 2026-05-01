package com.aibridge.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.aibridge.server.AiBridgeGateway
import com.aibridge.settings.AiBridgeSettings

/**
 * Project startup hook that auto-starts AiBridge when enabled in settings.
 */
class AiBridgeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = AiBridgeSettings.instance
        if (settings.autoStart) {
            val gateway = ApplicationManager.getApplication().getService(AiBridgeGateway::class.java)
            println("[AiBridge] Project opened: auto-starting server")
            gateway.start()
        } else {
            println("[AiBridge] Project opened: auto-start is disabled")
        }
    }
}
