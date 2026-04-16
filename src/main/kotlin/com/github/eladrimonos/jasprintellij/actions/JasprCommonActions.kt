package com.github.eladrimonos.jasprintellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent

class JasprBuildAction : JasprTerminalAction("Jaspr Build", listOf("build")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.text = "Jaspr Build"
    }
}

class JasprDoctorAction : JasprTerminalAction("Jaspr Doctor", listOf("doctor")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.text = "Jaspr Doctor"
    }
}

class JasprCleanAction : JasprTerminalAction("Jaspr Clean", listOf("clean")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.text = "Jaspr Clean"
    }
}

class JasprUpdateCliAction : JasprTerminalAction("Update Jaspr CLI", emptyList(), isGlobalActivation = true) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.text = "Update Jaspr CLI"
    }
}