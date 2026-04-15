package com.intelliaibridge.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intelliaibridge.intellij.server.IntelliAiBridgeGateway

/** IntelliJ action that starts the IntelliAiBridge gateway service. */
class StartServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().getService(IntelliAiBridgeGateway::class.java).start()
    }
}

/** IntelliJ action that stops the IntelliAiBridge gateway service. */
class StopServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().getService(IntelliAiBridgeGateway::class.java).stop()
    }
}
