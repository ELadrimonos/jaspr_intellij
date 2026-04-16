package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

import com.intellij.ide.fileTemplates.FileTemplateManager
import java.util.Properties

class ConvertHtmlToJasprAction : AnAction() {
    private val logger = Logger.getInstance(ConvertHtmlToJasprAction::class.java)
    // Only visible for .html files in a Jaspr project
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null &&
                    file?.extension == "html" &&
                    JasprTooling.isJasprProject(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val outputDir = Messages.showInputDialog(
            project,
            "Enter output directory (relative to project root):",
            "Convert HTML to Jaspr",
            null,
            "lib/components",
            null
        ) ?: return // User cancelled

        logger.info("ConvertHtmlToJaspr: triggered for file=${file.path}, outputDir=$outputDir")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Converting HTML to Jaspr…", false) {
            override fun run(indicator: ProgressIndicator) {
                val daemon = project.getService(JasprToolingDaemonService::class.java)
                logger.info("ConvertHtmlToJaspr: daemon=$daemon, isAlive=${daemon?.isAlive}")

                if (daemon == null || !daemon.isAlive) {
                    logger.warn("ConvertHtmlToJaspr: daemon is not running")
                    notifyError(project, "Jaspr tooling daemon is not running.")
                    return
                }

                val html = readFile(file) ?: run {
                    logger.warn("ConvertHtmlToJaspr: could not read file=${file.path}")
                    notifyError(project, "Could not read ${file.name}.")
                    return
                }


                val htmlToConvert = extractBodyContent(html)
                logger.info("ConvertHtmlToJaspr: sending to daemon, length=${htmlToConvert.length}, preview=${htmlToConvert.take(200)}")

                val dartCode = daemon.convertHtml(htmlToConvert)
                logger.info("ConvertHtmlToJaspr: dartCode=${dartCode?.take(300) ?: "NULL"}")

                if (dartCode == null) {
                    notifyError(project, "Conversion failed. Check that jaspr_cli >= 0.21.2 is installed.")
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    createAndOpenDartFile(project, file, dartCode, outputDir)
                }
            }
        })
    }

    private fun extractBodyContent(html: String): String {
        val bodyRegex = Regex("""<body[^>]*>(.*?)</body>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val bodyMatch = bodyRegex.find(html)
        val content = if (bodyMatch != null) {
            bodyMatch.groupValues[1].trim()
        } else {
            html.replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), "").trim()
        }

        // Wrap in a single root so the daemon's parseFragment.firstChild gets everything
        return "<div>$content</div>"
    }

    private fun readFile(file: VirtualFile): String? = try {
        VfsUtil.loadText(file)
    } catch (e: Exception) {
        null
    }

    private fun wrapInComponent(project: Project, dartCode: String, componentName: String): String {
        val template = FileTemplateManager.getInstance(project).getInternalTemplate("StatelessComponent.dart")
        val properties = Properties(FileTemplateManager.getInstance(project).defaultProperties)
        properties.setProperty("NAME", componentName)
        properties.setProperty("BODY", dartCode)
        return template.getText(properties)
    }

    private fun createAndOpenDartFile(project: Project, sourceFile: VirtualFile, dartCode: String, outputDirPath: String) {
        ApplicationManager.getApplication().runWriteAction {
            try {
                val projectDir = project.guessProjectDir() ?: run {
                    notifyError(project, "Could not find project directory.")
                    return@runWriteAction
                }

                val parent = VfsUtil.createDirectoryIfMissing(projectDir, outputDirPath) ?: run {
                    notifyError(project, "Could not create or find directory: $outputDirPath")
                    return@runWriteAction
                }

                val baseName = sourceFile.nameWithoutExtension
                    .replaceFirstChar { it.uppercase() }
                    .replace(Regex("[^a-zA-Z0-9_]"), "_")

                val componentName = "${baseName}Component"

                var targetName = "${baseName.lowercase()}.dart"
                var i = 1
                while (parent.findChild(targetName) != null) {
                    targetName = "${baseName.lowercase()}_$i.dart"
                    i++
                }

                val dartFile = parent.createChildData(this, targetName)
                VfsUtil.saveText(dartFile, wrapInComponent(project, dartCode, componentName))
                FileEditorManager.getInstance(project).openFile(dartFile, true)
            } catch (e: Exception) {
                notifyError(project, "Could not create Dart file: ${e.message}")
            }
        }
    }

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}