package com.github.eladrimonos.jasprintellij.services

import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

@Service(Service.Level.PROJECT)
class JasprToolingDaemonService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(JasprToolingDaemonService::class.java)
    private var processHandler: KillableColoredProcessHandler? = null
    var daemonVersion: String? = null
    var daemonPid: Long? = null

    val isAlive: Boolean
        get() = processHandler?.isProcessTerminated == false

    fun start() {
        if (processHandler != null && !processHandler!!.isProcessTerminated) {
            return
        }

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return
        
        // Ensure jaspr_cli is available
        val tooling = JasprTooling()
        try {
            tooling.preflightCheck(sdkPath)
        } catch (e: Exception) {
            logger.warn("Jaspr Tooling Daemon: preflight check failed. ${e.message}")
            return
        }

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "tooling-daemon")
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath)

        try {
            val handler = KillableColoredProcessHandler(cmd)
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trim()
                    if (text.isNotEmpty()) {
                        logger.info("Jaspr Daemon: $text")
                        if (text.startsWith("[{\"event\":\"daemon.connected\"")) {
                            parseConnectionEvent(text)
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    logger.info("Jaspr Daemon terminated with exit code ${event.exitCode}")
                    processHandler = null
                    daemonVersion = null
                    daemonPid = null
                }

                override fun startNotified(event: ProcessEvent) {}
                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
            })

            processHandler = handler
            handler.startNotify()
            logger.info("Jaspr Tooling Daemon started for project ${project.name}")
        } catch (e: Exception) {
            logger.error("Failed to start Jaspr Tooling Daemon", e)
        }
    }

    private fun parseConnectionEvent(text: String) {
        try {
            // Very simple parsing since we don't have a JSON lib in scope yet
            // Example: [{"event":"daemon.connected","params":{"version":"0.4.2","pid":62597}}]
            val versionMatch = Regex("\"version\":\"([^\"]+)\"").find(text)
            val pidMatch = Regex("\"pid\":(\\d+)").find(text)

            daemonVersion = versionMatch?.groupValues?.getOrNull(1)
            daemonPid = pidMatch?.groupValues?.getOrNull(1)?.toLongOrNull()

            logger.info("Jaspr Daemon connected: version=$daemonVersion, pid=$daemonPid")
        } catch (e: Exception) {
            logger.warn("Failed to parse connection event: $text")
        }
    }

    override fun dispose() {
        stop()
    }

    fun stop() {
        processHandler?.let {
            if (!it.isProcessTerminated) {
                it.destroyProcess()
            }
            processHandler = null
        }
    }
}
