package com.aibridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.aibridge.server.AiBridgeGateway

/** Action that starts the AiBridge gateway service. */
class StartServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().getService(AiBridgeGateway::class.java).start()
    }
}

/** Action that stops the AiBridge gateway service. */
class StopServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().getService(AiBridgeGateway::class.java).stop()
    }
}
