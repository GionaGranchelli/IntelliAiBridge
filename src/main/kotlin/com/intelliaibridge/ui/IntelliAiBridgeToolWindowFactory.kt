package com.intelliaibridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intelliaibridge.server.IntelliAiBridgeGateway
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Tool window UI showing gateway status, controls, and runtime logs.
 */
class IntelliAiBridgeToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Creates and wires tool window content for one project instance. */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val gateway = ApplicationManager.getApplication().getService(IntelliAiBridgeGateway::class.java)
        
        // Stats Panel
        val totalRequestsLabel = JBLabel("Total Requests: 0")
        val uptimeLabel = JBLabel("Uptime: 0s")
        val activeRequestsLabel = JBLabel("Active Requests: 0")
        val statusLabel = JBLabel("Status: Stopped")

        val statsPanel = panel {
            row { cell(statusLabel) }
            row { cell(totalRequestsLabel) }
            row { cell(activeRequestsLabel) }
            row { cell(uptimeLabel) }
            row {
                button("Start") { gateway.start() }
                button("Stop") { gateway.stop() }
                button("Restart") { gateway.stop(); gateway.start() }
            }
        }

        // Log Panel
        val logArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            font = com.intellij.util.ui.JBUI.Fonts.label()
        }
        val logScrollPane = JBScrollPane(logArea)
        
        val logListener = object : IntelliAiBridgeGateway.LogListener {
            override fun onLog(message: String) {
                ApplicationManager.getApplication().invokeLater {
                    logArea.append(message + "\n")
                    logArea.caretPosition = logArea.document.length
                }
            }
        }
        gateway.addLogListener(logListener)

        // Main Layout
        val mainPanel = JPanel(BorderLayout()).apply {
            add(statsPanel, BorderLayout.NORTH)
            add(logScrollPane, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // Timer to update stats
        val statsTimer = Timer(1000) {
            val stats = gateway.getStats()
            val total = stats["totalRequests"] as Int
            val uptime = (stats["uptime"] as Long) / 1000
            val active = stats["activeRequests"] as Int
            val running = gateway.isRunning()

            totalRequestsLabel.text = "Total Requests: $total"
            uptimeLabel.text = "Uptime: ${uptime}s"
            activeRequestsLabel.text = "Active Requests: $active"
            statusLabel.text = "Status: ${if (running) "Running" else "Stopped"}"
            statusLabel.foreground = if (running) com.intellij.ui.JBColor.GREEN else com.intellij.ui.JBColor.RED
        }
        statsTimer.start()

        content.setDisposer(Disposable {
            statsTimer.stop()
            gateway.removeLogListener(logListener)
        })
    }
}
