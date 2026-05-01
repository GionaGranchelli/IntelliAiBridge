package com.aibridge.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.aibridge.server.AiBridgeGateway
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Registers AiBridge status widget in status bar.
 */
class AiBridgeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "AiBridgeStatusBarWidget"
    override fun getDisplayName(): String = "AiBridge Status"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = AiBridgeStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {}
}

/**
 * Status bar icon presentation for toggling AiBridge server state.
 */
class AiBridgeStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.IconPresentation {
    private val gateway = com.intellij.openapi.application.ApplicationManager.getApplication()
        .getService(AiBridgeGateway::class.java)
    private val icon: Icon = IconLoader.getIcon("/icons/aibridge.svg", AiBridgeStatusBarWidget::class.java)

    override fun ID(): String = "AiBridgeStatusBarWidget"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getTooltipText(): String = if (gateway.isRunning()) "AiBridge: Running" else "AiBridge: Stopped"
    
    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        if (gateway.isRunning()) gateway.stop() else gateway.start()
        myStatusBar?.updateWidget(ID())
    }

    override fun getIcon(): Icon? = icon
}
