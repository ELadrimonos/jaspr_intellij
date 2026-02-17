package com.github.eladrimonos.jasprintellij.startup

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
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

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return

        val tooling = JasprTooling()

        val cliVersion = tooling.readJasprCliVersion(sdkPath)
        val frameworkVersion = tooling.readFrameworkVersionFromPubspec(projectDir)

        if (cliVersion.isNullOrBlank() || frameworkVersion.isNullOrBlank()) return
        if (cliVersion == frameworkVersion) return

        // TODO Add options to update either jaspr_cli or pubspec.yaml
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
}