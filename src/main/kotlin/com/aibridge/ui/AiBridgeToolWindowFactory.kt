package com.aibridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.aibridge.server.AiBridgeGateway
import com.aibridge.server.ModelInfo
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer

/**
 * Tool window UI showing gateway status, controls, and runtime logs.
 */
class AiBridgeToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Creates and wires tool window content for one project instance. */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val gateway = ApplicationManager.getApplication().getService(AiBridgeGateway::class.java)
        
        // Stats Panel
        val totalRequestsLabel = JBLabel("Total Requests: 0")
        val uptimeLabel = JBLabel("Uptime: 0s")
        val activeRequestsLabel = JBLabel("Active Requests: 0")
        val statusLabel = JBLabel("Status: Stopped")
        val modelsLabel = JBLabel("Models: 0")

        val statsPanel = panel {
            group("Status") {
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
            group("Available Models") {
                row { cell(modelsLabel) }
                row { label("Click a model to copy its ID to clipboard").applyToComponent { 
                    foreground = com.intellij.ui.JBColor.GRAY
                    font = com.intellij.util.ui.JBUI.Fonts.smallFont()
                } }
            }
        }

        // Models List
        val modelsListModel = DefaultListModel<ModelInfo>()
        val modelsList = JBList(modelsListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "Discovering models..."
            cellRenderer = object : SimpleListCellRenderer<ModelInfo>() {
                override fun customize(list: JList<out ModelInfo>, value: ModelInfo?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.label ?: value?.id ?: ""
                    border = com.intellij.util.ui.JBUI.Borders.empty(2, 5)
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val model = modelsListModel.getElementAt(index)
                        CopyPasteManager.getInstance().setContents(StringSelection(model.id))
                    }
                }
            })
        }
        val modelsScrollPane = JBScrollPane(modelsList).apply {
            preferredSize = java.awt.Dimension(-1, 150)
        }

        // Log Panel
        val logArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            font = com.intellij.util.ui.JBUI.Fonts.label()
        }
        val logScrollPane = JBScrollPane(logArea)
        
        val logListener = object : AiBridgeGateway.LogListener {
            override fun onLog(message: String) {
                ApplicationManager.getApplication().invokeLater {
                    logArea.append(message + "\n")
                    logArea.caretPosition = logArea.document.length
                }
            }
        }
        gateway.addLogListener(logListener)

        // Main Layout
        val topPanel = JPanel(BorderLayout()).apply {
            add(statsPanel, BorderLayout.NORTH)
            add(modelsScrollPane, BorderLayout.CENTER)
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(logScrollPane, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        var lastModels: List<ModelInfo> = emptyList()

        // Timer to update stats
        val statsTimer = Timer(2000) {
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

            val currentModels = gateway.listModels()
            if (currentModels != lastModels) {
                lastModels = currentModels
                modelsLabel.text = "Models: ${currentModels.size}"
                modelsListModel.clear()
                currentModels.forEach { modelsListModel.addElement(it) }
            }
        }
        statsTimer.start()

        content.setDisposer(Disposable {
            statsTimer.stop()
            gateway.removeLogListener(logListener)
        })
    }
}
