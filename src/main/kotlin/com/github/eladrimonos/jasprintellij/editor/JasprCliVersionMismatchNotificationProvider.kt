package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.services.JasprPackageVersion
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * Warns, above each Dart file, when the jaspr version of the file's own package (not just
 * the first Jaspr package of a monorepo) differs in major.minor from the globally installed
 * jaspr_cli, which is what the plugin always runs.
 */
class JasprCliVersionMismatchNotificationProvider : EditorNotificationProvider, DumbAware {

    private val tooling = JasprTooling()

    // Package directories whose warning was dismissed, until the IDE restarts.
    private val dismissed = ConcurrentHashMap.newKeySet<String>()

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "dart") return null
        val cliVersion = project.getService(JasprToolingDaemonService::class.java)?.cliVersion ?: return null
        val resolved = JasprPackageVersion.of(project, file) ?: return null
        if (resolved.packageDir.path in dismissed) return null
        if (!tooling.isVersionMismatch(cliVersion, resolved.version)) return null

        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = JasprBundle.message(
                    "jaspr.editor.cli.version.mismatch",
                    resolved.packageDir.name, resolved.version, resolved.source, cliVersion,
                )
                createActionLabel(JasprBundle.message("jaspr.editor.cli.version.mismatch.dismiss")) {
                    dismissed += resolved.packageDir.path
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }
}
