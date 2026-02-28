package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup

class JasprActionGroup : DefaultActionGroup() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && JasprTooling.isJasprProject(project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
