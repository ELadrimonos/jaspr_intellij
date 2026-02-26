package com.github.eladrimonos.jasprintellij.startup

import com.github.eladrimonos.jasprintellij.execution.JasprConfigurationFactory
import com.github.eladrimonos.jasprintellij.execution.JasprRunConfiguration
import com.github.eladrimonos.jasprintellij.execution.JasprRunConfigurationType
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

class JasprProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val projectDir = File(basePath)

        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return
        val pubspecContent = pubspec.readText()
        if (!pubspecContent.contains("jaspr")) return

        addDefaultRunConfigurationIfNeeded(project, pubspecContent)

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: run {
            return
        }

        val tooling = JasprTooling()
        val cliVersion = tooling.readJasprCliVersion(sdkPath)
        val frameworkVersion = tooling.readFrameworkVersionFromPubspec(projectDir)

        if (cliVersion.isNullOrBlank() || frameworkVersion.isNullOrBlank()) return
        if (cliVersion == frameworkVersion) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(
                "Jaspr version mismatch",
                "Project uses <b>$frameworkVersion</b> (pubspec.yaml) but jaspr_cli <b>$cliVersion</b> is installed.<br/>" +
                        "It is recommended to use the same version.",
                NotificationType.WARNING
            )
            .setIcon(JasprIcons.FileIcon)
            .notify(project)
    }

    private fun addDefaultRunConfigurationIfNeeded(
        project: Project,
        pubspecContent: String,
    ) {
        val runManager = RunManager.getInstance(project)
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)

        val existing = runManager.getConfigurationsList(type)
        if (existing.isNotEmpty()) return

        val settings = runManager.createConfiguration("Jaspr", factory)
        val config = settings.configuration as JasprRunConfiguration

        val isServerProject = pubspecContent.contains("shelf") ||
                pubspecContent.contains("jaspr_server")
        config.mode = if (isServerProject) "reload" else "refresh"

        settings.isActivateToolWindowBeforeRun = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
    }
}