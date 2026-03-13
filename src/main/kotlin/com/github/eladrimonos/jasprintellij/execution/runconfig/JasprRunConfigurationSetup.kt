package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Shared helper for creating the default Jaspr run configuration.
 *
 * Called from two places:
 * - [com.github.eladrimonos.jasprintellij.template.project.JasprDirectoryProjectGenerator.generateProject] — right after the CLI
 *   scaffolds the project, so the run config is ready without a reopen.
 * - [com.github.eladrimonos.jasprintellij.startup.JasprProjectActivity] — as
 *   a safety net for projects opened without going through the wizard (e.g.
 *   cloned repos, upgraded plugin). The idempotency guard makes the second
 *   call a no-op.
 */
object JasprRunConfigurationSetup {

    /**
     * Reads pubspec.yaml and schedules the run config creation on the EDT if needed.
     * Must be called from a background/IO thread — performs file I/O.
     */
    fun addIfNeeded(project: Project, projectDir: File) {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return
        val content = pubspec.readText()
        if (!content.contains("jaspr")) return

        val isServerProject = content.contains("shelf") || content.contains("jaspr_server")
        ApplicationManager.getApplication().invokeLater {
            applyOnEdt(project, isServerProject)
        }
    }

    /**
     * Creates and selects the default Jaspr run configuration.
     * Must be called on the EDT. Use [addIfNeeded] when calling from a background thread.
     */
    fun applyOnEdt(project: Project, isServerProject: Boolean) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (project.isDisposed) return

        val runManager = RunManager.getInstance(project)
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)

        if (runManager.getConfigurationsList(type).isNotEmpty()) return

        val settings = runManager.createConfiguration("Serve", factory)
        val config = settings.configuration as JasprRunConfiguration
        config.mode = if (isServerProject) "reload" else "refresh"

        settings.isActivateToolWindowBeforeRun = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
    }
}