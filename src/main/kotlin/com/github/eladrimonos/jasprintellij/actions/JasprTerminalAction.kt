package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

abstract class JasprTerminalAction(
    private val actionTitle: String,
    private val jasprArgs: List<String>,
    private val isGlobalActivation: Boolean = false
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: run {
            notifyError(project)
            return
        }

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath)

        if (isGlobalActivation) {
            cmd.addParameters("pub", "global", "activate", "jaspr_cli")
        } else {
            cmd.addParameters("pub", "global", "run", "jaspr_cli:jaspr")
            cmd.addParameters(jasprArgs)
        }

        val processHandler = KillableColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(processHandler)

        val executor: Executor = DefaultRunExecutor.getRunExecutorInstance()
        val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        consoleView.attachToProcess(processHandler)

        val descriptor = RunContentDescriptor(
            consoleView,
            processHandler,
            consoleView.component,
            actionTitle,
            JasprIcons.JasprLogo
        )

        RunContentManager.getInstance(project).showRunContent(executor, descriptor)
        processHandler.startNotify()
    }

    private fun notifyError(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JasprIntelliJ")
            .createNotification("Jaspr", "Dart SDK is not configured for this project.", NotificationType.ERROR)
            .setIcon(JasprIcons.JasprLogo)
            .notify(project)
    }
}