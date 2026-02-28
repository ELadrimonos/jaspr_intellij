package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

// TODO Remove for release or show only on plugin debug
class JasprDaemonStatusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val daemonService = project.getService(JasprToolingDaemonService::class.java) ?: return

        val statusText = if (daemonService.isAlive) {
            "Jaspr Tooling Daemon is <b>RUNNING</b> (PID: ${daemonService.daemonPid ?: "Unknown"}, Version: ${daemonService.daemonVersion ?: "Unknown"})"
        } else {
            "Jaspr Tooling Daemon is <b>STOPPED</b>."
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification("Jaspr daemon status", statusText, NotificationType.INFORMATION)
            .setIcon(JasprIcons.JasprLogo)
            .notify(project)
    }
}
