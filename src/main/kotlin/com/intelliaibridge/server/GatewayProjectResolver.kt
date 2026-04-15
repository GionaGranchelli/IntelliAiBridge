package com.intelliaibridge.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Resolves IntelliJ project context for incoming requests.
 */
internal class GatewayProjectResolver(
    private val log: (String) -> Unit
) {
    /**
     * Selects project context for a request, honoring `X-IntelliAiBridge-Project`
     * when provided and falling back to deterministic default selection.
     */
    fun resolveProject(call: ApplicationCall): Project? {
        val requested = call.request.header("X-IntelliAiBridge-Project")?.trim().orEmpty()
        if (requested.isNotBlank()) {
            val selected = findProjectBySelector(requested)
            if (selected != null) {
                return selected
            }
            log("Requested project '$requested' not found. Falling back to deterministic default project.")
        }
        return selectDefaultProject()
    }

    fun selectDefaultProject(): Project? {
        return ProjectManager.getInstance().openProjects
            .sortedWith(compareBy<Project>({ it.basePath ?: "" }, { it.name }))
            .firstOrNull()
    }

    private fun findProjectBySelector(selector: String): Project? {
        val normalized = selector.lowercase()
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val projectName = project.name.lowercase()
            val basePath = project.basePath?.lowercase()
            projectName == normalized ||
                basePath == normalized ||
                basePath?.substringAfterLast('/') == normalized
        }
    }
}
