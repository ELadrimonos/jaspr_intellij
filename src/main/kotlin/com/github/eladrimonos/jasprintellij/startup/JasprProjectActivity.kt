package com.github.eladrimonos.jasprintellij.startup

import com.github.eladrimonos.jasprintellij.execution.JasprRunConfigurationSetup
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.module.JasprModuleConfigurator
import com.github.eladrimonos.jasprintellij.services.JasprTooling
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

        // Safety net: creates the run config if the project was opened without
        // going through the wizard (cloned repo, upgraded plugin, etc.).
        // When the wizard already called addIfNeeded, the idempotency guard
        // inside makes this a no-op.
        JasprRunConfigurationSetup.addIfNeeded(project, projectDir)

        // Ensure all modules are configured as Dart/Jaspr modules.
        JasprModuleConfigurator.ensureConfigured(project)

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return

        val tooling = JasprTooling()
        val cliVersion = tooling.readJasprCliVersion(sdkPath)
        val frameworkVersion = tooling.readFrameworkVersionFromPubspec(projectDir)

        if (cliVersion.isNullOrBlank() || frameworkVersion.isNullOrBlank()) return
        if (cliVersion == frameworkVersion) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(
                "Jaspr version mismatch",
                "Project uses <b>$frameworkVersion</b> (pubspec.yaml) but jaspr_cli " +
                        "<b>$cliVersion</b> is installed.<br/>" +
                        "It is recommended to use the same version.",
                NotificationType.WARNING
            )
            .setIcon(JasprIcons.FileIcon)
            .notify(project)
    }
}