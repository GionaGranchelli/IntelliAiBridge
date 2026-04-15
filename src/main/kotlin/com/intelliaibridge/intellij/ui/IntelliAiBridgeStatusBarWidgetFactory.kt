package com.intelliaibridge.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.intelliaibridge.intellij.server.IntelliAiBridgeGateway
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Registers IntelliAiBridge status widget in IntelliJ status bar.
 */
class IntelliAiBridgeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "IntelliAiBridgeStatusBarWidget"
    override fun getDisplayName(): String = "IntelliAiBridge Status"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = IntelliAiBridgeStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {}
}

/**
 * Status bar icon presentation for toggling IntelliAiBridge server state.
 */
class IntelliAiBridgeStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.IconPresentation {
    private val gateway = com.intellij.openapi.application.ApplicationManager.getApplication()
        .getService(IntelliAiBridgeGateway::class.java)
    private val icon: Icon = IconLoader.getIcon("/icons/intelliaibridge.svg", IntelliAiBridgeStatusBarWidget::class.java)

    override fun ID(): String = "IntelliAiBridgeStatusBarWidget"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getTooltipText(): String = if (gateway.isRunning()) "IntelliAiBridge: Running" else "IntelliAiBridge: Stopped"
    
    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        if (gateway.isRunning()) gateway.stop() else gateway.start()
        myStatusBar?.updateWidget(ID())
    }

    override fun getIcon(): Icon? = icon
}
