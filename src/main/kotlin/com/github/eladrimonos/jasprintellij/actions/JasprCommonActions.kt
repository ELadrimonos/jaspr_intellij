package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager

// Menu text and descriptions come from plugin.xml; the titles below name the Run tool window tab.

class JasprBuildAction : JasprTerminalAction(JasprBundle.message("jaspr.action.build.title"), listOf("build"))

class JasprDoctorAction : JasprTerminalAction(JasprBundle.message("jaspr.action.doctor.title"), listOf("doctor"))

class JasprCleanAction : JasprTerminalAction(JasprBundle.message("jaspr.action.clean.title"), listOf("clean"))

class JasprUpdateCliAction : JasprTerminalAction(
    JasprBundle.message("jaspr.action.update.cli.title"), emptyList(), isGlobalActivation = true,
) {
    // Re-read the CLI version so run configurations and scope tooling pick up the update.
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runAction(project) {
            ApplicationManager.getApplication().executeOnPooledThread {
                project.getService(JasprToolingDaemonService::class.java)?.restart()
            }
        }
    }
}
