package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Shared helper for creating the default Jaspr run configuration.
 *
 * Called from two places:
 * - [JasprDirectoryProjectGenerator.generateProject] — right after the CLI
 *   scaffolds the project, so the run config is ready without a reopen.
 * - [com.github.eladrimonos.jasprintellij.startup.JasprProjectActivity] — as
 *   a safety net for projects opened without going through the wizard (e.g.
 *   cloned repos, upgraded plugin). The idempotency guard makes the second
 *   call a no-op.
 */
object JasprRunConfigurationSetup {

    /**
     * Reads `pubspec.yaml` from [projectDir] and, if this looks like a Jaspr
     * project that has no run configuration yet, creates and selects a default one.
     *
     * Must be called on the EDT (it writes to [RunManager]).
     * Use [scheduleOnEdt] when calling from a background/progress thread.
     */
    fun addIfNeeded(project: Project, projectDir: File) {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return
        val pubspecContent = pubspec.readText()
        if (!pubspecContent.contains("jaspr")) return

        val runManager = RunManager.getInstance(project)
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)

        // Idempotency: do nothing if a Jaspr run config already exists.
        if (runManager.getConfigurationsList(type).isNotEmpty()) return

        val settings = runManager.createConfiguration("Serve", factory)
        val config = settings.configuration as JasprRunConfiguration

        val isServerProject = pubspecContent.contains("shelf") ||
                pubspecContent.contains("jaspr_server")
        config.mode = if (isServerProject) "reload" else "refresh"

        settings.isActivateToolWindowBeforeRun = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
    }

    /**
     * Convenience wrapper: schedules [addIfNeeded] on the EDT.
     * Safe to call from any background thread (e.g. inside
     * `runProcessWithProgressSynchronously`).
     */
    fun scheduleOnEdt(project: Project, projectDir: File) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                addIfNeeded(project, projectDir)
            }
        }
    }
}