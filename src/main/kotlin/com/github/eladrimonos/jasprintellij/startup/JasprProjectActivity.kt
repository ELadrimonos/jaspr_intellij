package com.github.eladrimonos.jasprintellij.startup

import com.github.eladrimonos.jasprintellij.actions.JasprUpdateCliAction
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationSetup
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.module.JasprModuleConfigurator
import com.github.eladrimonos.jasprintellij.services.JasprCliMissingException
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
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
            val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return@withContext
            val tooling = JasprTooling()

            try {
                tooling.preflightCheck(sdkPath)
                project.getService(JasprToolingDaemonService::class.java)?.start()
            } catch (e: Exception) {
                if (e is JasprCliMissingException) {
                    withContext(Dispatchers.Main) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("JasprIntelliJ")
                            .createNotification(
                                "Jaspr Tooling Error",
                                "Preflight check failed: ${e.localizedMessage?.substringBefore("\n") ?: "Unknown error"}",
                                NotificationType.ERROR
                            )
                            .setIcon(JasprIcons.JasprLogo)
                            .addAction(NotificationAction.create("Install jaspr_cli") { _, notification ->
                                JasprUpdateCliAction().runAction(project) {
                                    // Restart daemon after installation
                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        project.getService(JasprToolingDaemonService::class.java)?.start()
                                    }
                                }
                                notification.expire()
                            })
                            .notify(project)
                    }
                } else {
                    // Log other preflight errors
                    com.intellij.openapi.diagnostic.Logger.getInstance(JasprProjectActivity::class.java)
                        .warn("Jaspr preflight check failed: ${e.localizedMessage}")
                }
                return@withContext
            }

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
