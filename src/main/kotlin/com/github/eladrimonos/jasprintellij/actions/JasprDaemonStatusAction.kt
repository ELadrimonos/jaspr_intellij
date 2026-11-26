package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class JasprDaemonStatusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val daemonService = project.getService(JasprToolingDaemonService::class.java) ?: return

        val statusText = if (daemonService.isAlive) {
            if (daemonService.cliVersion != null && daemonService.daemonPid == null) {
                JasprBundle.message("jaspr.status.active", daemonService.cliVersion!!)
            } else {
                JasprBundle.message(
                    "jaspr.status.running",
                    daemonService.daemonPid?.toString() ?: JasprBundle.message("jaspr.unknown"),
                    daemonService.daemonVersion ?: JasprBundle.message("jaspr.unknown"),
                )
            }
        } else {
            JasprBundle.message("jaspr.status.stopped")
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(JasprBundle.message("jaspr.status.title"), statusText, NotificationType.INFORMATION)
            .setIcon(JasprIcons.JasprLogo)
            .notify(project)
    }
}
