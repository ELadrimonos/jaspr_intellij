package com.github.eladrimonos.jasprintellij.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotifications

/** Re-evaluates the CLI version mismatch banner when a pubspec.yaml or pubspec.lock changes. */
class JasprPubspecChangeListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        if (events.any { it.path.endsWith("/pubspec.yaml") || it.path.endsWith("/pubspec.lock") }) {
            EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }
}
