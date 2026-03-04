package com.github.eladrimonos.jasprintellij.startup

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationSetup
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.module.JasprModuleConfigurator
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JasprProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!JasprTooling.isJasprProject(project)) return
        val basePath = project.basePath ?: return
        val projectDir = File(basePath)

        JasprRunConfigurationSetup.addIfNeeded(project, projectDir)
        JasprModuleConfigurator.ensureConfigured(project)

        withContext(Dispatchers.IO) {
            project.getService(JasprToolingDaemonService::class.java)?.start()

            val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return@withContext
            val tooling = JasprTooling()
            val cliVersion = tooling.readJasprCliVersion(sdkPath)
            val frameworkVersion = tooling.readFrameworkVersionFromPubspec(projectDir)

            if (cliVersion.isNullOrBlank() || frameworkVersion.isNullOrBlank()) return@withContext
            if (cliVersion == frameworkVersion) return@withContext

            // La notificación vuelve al hilo principal
            withContext(Dispatchers.Main) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("JasprIntelliJ")
                    .createNotification(
                        "Jaspr version mismatch",
                        "Project uses <b>$frameworkVersion</b> (pubspec.yaml) but jaspr_cli " +
                                "<b>$cliVersion</b> is installed.<br/>" +
                                "It is recommended to use the same version.",
                        NotificationType.WARNING
                    )
                    .setIcon(JasprIcons.JasprLogo)
                    .notify(project)
            }
        }
    }
}