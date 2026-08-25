package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.MelosScriptInjector
import com.github.eladrimonos.jasprintellij.services.MelosWorkspaceDetector
import com.intellij.execution.RunManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import org.yaml.snakeyaml.Yaml

private const val MELOS_INJECTION_DISMISSED_KEY = "jaspr.melos.injection.dismissed"
private const val DEFAULT_MELOS_SCRIPT_NAME = "serve_jaspr"

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

    /**
     * Checks whether Melos injection was dismissed by the user for this project.
     * Safe to call from any thread — reads a lightweight, in-memory-backed property.
     */
    fun isMelosInjectionDismissed(project: Project): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(MELOS_INJECTION_DISMISSED_KEY, false)

    /**
     * Marks Melos auto-injection as dismissed for this project, so [com.github.eladrimonos.jasprintellij.startup.JasprProjectActivity]
     * stops offering/re-applying it on future project opens. Called both from the
     * "Don't ask again" notification action and when the user manually clears the
     * "Melos script" field in a run configuration — either is a clear enough signal
     * they don't want this managed automatically.
     */
    fun markMelosInjectionDismissed(project: Project) {
        PropertiesComponent.getInstance(project).setValue(MELOS_INJECTION_DISMISSED_KEY, true)
    }

    /**
     * Notifies the user that [projectDir] is part of a Dart/Melos workspace, injects the
     * auto-generated Melos script into [MelosWorkspaceDetector.WorkspaceInfo.melosConfigFile],
     * and points a Jaspr run configuration at that script.
     *
     * Must be called on the EDT — performs file I/O for the injection, which is acceptable
     * here as it is a single small, idempotent read+write triggered once per project open.
     */
    fun notifyAndConfigureForMelos(project: Project, projectDir: File, info: MelosWorkspaceDetector.WorkspaceInfo) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (project.isDisposed) return

        val runManager = RunManager.getInstance(project)
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)

        val configs = runManager.getConfigurationsList(type).mapNotNull { it as? JasprRunConfiguration }
        if (configs.any { it.melosScript.isNotBlank() }) return

        val packageName = readPackageName(projectDir) ?: projectDir.name
        val scriptName = DEFAULT_MELOS_SCRIPT_NAME

        val injectionResult = MelosScriptInjector.ensureScript(info.melosConfigFile, scriptName, packageName)
        if (injectionResult is MelosScriptInjector.InjectionResult.ConflictExistingMelosSection) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("JasprIntelliJ")
                .createNotification("Melos script conflict", injectionResult.message, NotificationType.WARNING)
                .setIcon(JasprIcons.JasprLogo)
                .notify(project)
            return
        }

        val settings = configs.firstOrNull()?.let { runManager.findSettings(it) }
            ?: runManager.createConfiguration("Serve (Melos)", factory).also {
                runManager.addConfiguration(it)
                runManager.selectedConfiguration = it
            }
        val config = settings.configuration as JasprRunConfiguration
        config.melosScript = scriptName

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(
                "Melos workspace detected",
                "This project is part of a Dart/Melos workspace. The Jaspr run configuration " +
                    "\"${settings.name}\" now runs via <code>melos run $scriptName</code>.",
                NotificationType.INFORMATION,
            )
            .setIcon(JasprIcons.JasprLogo)
            .addAction(NotificationAction.create("Don't ask again") { _, notification ->
                markMelosInjectionDismissed(project)
                notification.expire()
            })
            .notify(project)
    }

    private fun readPackageName(projectDir: File): String? {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = Yaml().load(pubspec.readText()) as? Map<String, Any?>
            map?.get("name") as? String
        } catch (e: Exception) {
            Logger.getInstance(JasprRunConfigurationSetup::class.java)
                .warn("Could not read package name from pubspec.yaml: ${e.message}")
            null
        }
    }
}