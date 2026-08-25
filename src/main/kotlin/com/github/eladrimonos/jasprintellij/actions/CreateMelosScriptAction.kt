package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.MelosScriptInjector
import com.github.eladrimonos.jasprintellij.services.MelosWorkspaceDetector
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Lets the user register an additional jaspr-daemon Melos script for a package in
 * this Dart/Melos workspace, without having to hand-edit melos.yaml/pubspec.yaml.
 */
class CreateMelosScriptAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val workspaceInfo = MelosWorkspaceDetector.detect(File(basePath))
        if (workspaceInfo == null) {
            notify(
                project, "Not a Melos workspace",
                "This project is not part of a Dart/Melos workspace (no 'workspace:' or 'melos:' " +
                    "section was found in pubspec.yaml, and no melos.yaml exists).",
                NotificationType.ERROR,
            )
            return
        }

        val knownPackageNames = MelosWorkspaceDetector.resolveMemberPackageNames(workspaceInfo)
        val dialog = CreateMelosScriptDialog(project, knownPackageNames)
        if (!dialog.showAndGet()) return

        when (val result = MelosScriptInjector.ensureScript(workspaceInfo.melosConfigFile, dialog.scriptName, dialog.packageName)) {
            is MelosScriptInjector.InjectionResult.ConflictExistingMelosSection ->
                notify(project, "Melos script conflict", result.message, NotificationType.WARNING)

            is MelosScriptInjector.InjectionResult.Written ->
                notify(
                    project, "Melos script created",
                    "Script \"${dialog.scriptName}\" now runs jaspr daemon for package \"${dialog.packageName}\" " +
                        "in ${workspaceInfo.melosConfigFile.name}. Select it as \"Melos script\" in a Jaspr " +
                        "Run Configuration to use it.",
                    NotificationType.INFORMATION,
                )
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(title, content, type)
            .setIcon(JasprIcons.JasprLogo)
            .notify(project)
    }
}
