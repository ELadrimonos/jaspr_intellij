package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.MelosScriptInjector
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.openapi.progress.ProgressManager
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
                project, JasprBundle.message("jaspr.notification.melos.not.workspace.title"),
                JasprBundle.message("jaspr.notification.melos.not.workspace.content"),
                NotificationType.ERROR,
            )
            return
        }

        val knownPackageNames = MelosWorkspaceDetector.resolveMemberPackageNames(workspaceInfo)
        val dialog = CreateMelosScriptDialog(project, knownPackageNames)
        if (!dialog.showAndGet()) return

        val melosVersion = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project)?.let { sdkPath ->
            ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>(
                { JasprTooling().readMelosVersion(sdkPath) }, JasprBundle.message("jaspr.progress.checking.melos"), false, project,
            )
        }
        val relativeDir = if (JasprTooling().isVersionAtLeast(melosVersion, MelosScriptInjector.INHERITED_STDIO_MELOS_VERSION)) {
            MelosWorkspaceDetector.resolveMemberPackageRelativeDir(workspaceInfo, dialog.packageName)
        } else null

        when (val result = MelosScriptInjector.ensureScript(
            workspaceInfo.melosConfigFile, dialog.scriptName, dialog.packageName, relativeDir,
        )) {
            is MelosScriptInjector.InjectionResult.ConflictExistingMelosSection ->
                notify(project, JasprBundle.message("jaspr.notification.melos.conflict.title"), result.message, NotificationType.WARNING)

            is MelosScriptInjector.InjectionResult.Written ->
                notify(
                    project, JasprBundle.message("jaspr.notification.melos.created.title"),
                    JasprBundle.message("jaspr.notification.melos.created.content", dialog.scriptName, dialog.packageName, workspaceInfo.melosConfigFile.name),
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
