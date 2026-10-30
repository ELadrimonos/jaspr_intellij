package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotifications

/**
 * Reacts to pubspec.yaml, pubspec.lock and melos.yaml changes: re-evaluates the CLI version
 * mismatch banner and re-discovers the workspace's Jaspr apps for scope hints.
 */
class JasprPubspecChangeListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val names = events.mapTo(HashSet()) { it.path.substringAfterLast('/') }
        if ("pubspec.yaml" in names || "pubspec.lock" in names) {
            EditorNotifications.getInstance(project).updateAllNotifications()
        }
        if ("pubspec.yaml" in names || "melos.yaml" in names) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (!project.isDisposed) project.getService(JasprToolingDaemonService::class.java)?.refreshApps()
            }
        }
    }
}
