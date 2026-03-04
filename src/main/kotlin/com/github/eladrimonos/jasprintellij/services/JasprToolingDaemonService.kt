package com.github.eladrimonos.jasprintellij.services

import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

@Service(Service.Level.PROJECT)
class JasprToolingDaemonService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(JasprToolingDaemonService::class.java)
    private var processHandler: KillableColoredProcessHandler? = null
    private val gson = Gson()

    var daemonVersion: String? = null
    var daemonPid: Long? = null

    private var nextId = 1

    /**
     * Maps normalized file paths to their component scope results.
     * Structure mirrors the VS Code ScopeResults type:
     *   { components: List<String>, serverScopeRoots: List<ScopeTarget>, clientScopeRoots: List<ScopeTarget> }
     */
    private val fileScopes = mutableMapOf<String, FileComponentScopes>()
    private val outputBuffer = StringBuilder()

    val isAlive: Boolean
        get() = processHandler?.isProcessTerminated == false

    fun start() {
        if (processHandler != null && !processHandler!!.isProcessTerminated) return

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return

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
                    synchronized(outputBuffer) {
                        outputBuffer.append(event.text)
                        val content = outputBuffer.toString()
                        val lastNewline = content.lastIndexOf('\n')
                        if (lastNewline >= 0) {
                            val completeLines = content.substring(0, lastNewline)
                            outputBuffer.delete(0, lastNewline + 1)
                            
                            completeLines.split("\n").forEach { line ->
                                val trimmedLine = line.trim()
                                if (trimmedLine.isNotEmpty()) {
                                    logger.debug("JASPR DAEMON RAW: $trimmedLine")
                                    processDaemonMessage(trimmedLine)
                                }
                            }
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    logger.warn("JASPR DAEMON terminated with exit code ${event.exitCode}")
                    processHandler = null
                    daemonVersion = null
                    daemonPid = null
                    nextId = 1
                    fileScopes.clear()
                    synchronized(outputBuffer) { outputBuffer.setLength(0) }
                }

                override fun startNotified(event: ProcessEvent) {}
                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
            })

            processHandler = handler
            handler.startNotify()
        } catch (e: Exception) {
            logger.error("Failed to start Jaspr Tooling Daemon", e)
        }
    }

    private fun processDaemonMessage(text: String) {
        try {
            if (text.startsWith("[")) {
                val listType = object : TypeToken<List<DaemonMessage>>() {}.type
                val messages: List<DaemonMessage> = gson.fromJson(text, listType)
                for (message in messages) {
                    handleMessage(message)
                }
            } else if (text.startsWith("{")) {
                val message: DaemonMessage = gson.fromJson(text, DaemonMessage::class.java)
                handleMessage(message)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse daemon message: $text", e)
        }
    }

    private fun handleMessage(message: DaemonMessage) {
        if (message.id != null) {
            logger.debug("JASPR DAEMON response for id=${message.id}: result=${message.result}, error=${message.error}")
        }
        if (message.event != null) {
            when (message.event) {
                "daemon.connected" -> handleConnectedEvent(message.params)
                "daemon.log" -> logger.debug("JASPR DAEMON log: ${message.params?.get("message")}")
                "scopes.result" -> handleScopesResultEvent(message.params)
                "scopes.status" -> logger.debug("JASPR DAEMON status: ${message.params}")
                else -> logger.debug("Unknown daemon event: ${message.event}")
            }
        }
    }

    private fun handleConnectedEvent(params: Map<String, Any>?) {
        daemonVersion = params?.get("version") as? String
        daemonPid = (params?.get("pid") as? Double)?.toLong()
        logger.warn("JASPR DAEMON connected: version=$daemonVersion, pid=$daemonPid")
        sendRegisterCommand()
    }

    private fun sendRegisterCommand() {
        val basePath = project.basePath ?: return
        val command = mapOf(
            "id" to nextId++,
            "method" to "scopes.register",
            "params" to mapOf("folders" to listOf(basePath))
        )
        logger.debug("JASPR DAEMON: registering folders: $basePath")
        sendCommand(command)
    }

    /**
     * Refreshes inlay hints for all currently open Dart files.
     * Must be called on the EDT.
     */
    fun refreshHintsForOpenFiles() {
        if (project.isDisposed) return
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val analyzer = DaemonCodeAnalyzer.getInstance(project)

        val openFiles = fileEditorManager.openFiles.filter { it.extension == "dart" }
        if (openFiles.isEmpty()) return

        logger.debug("JASPR DAEMON: forcing refresh for ${openFiles.size} open Dart files")

        for (vFile in openFiles) {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = psiManager.findFile(vFile)
                if (psiFile != null && psiFile.isValid) {
                    analyzer.restart(psiFile)
                }
            }
        }

        try {
            com.intellij.util.FileContentUtil.reparseFiles(project, openFiles, true)
        } catch (e: Exception) {
            logger.warn("JASPR DAEMON: FileContentUtil.reparseFiles failed: ${e.message}")
        }
    }

    private fun sendCommand(command: Any) {
        val json = gson.toJson(listOf(command))
        logger.debug("Sending command to Jaspr Daemon: $json")
        processHandler?.let {
            if (!it.isProcessTerminated) {
                val os = it.processInput
                os.write(json.toByteArray(StandardCharsets.UTF_8))
                os.write("\n".toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleScopesResultEvent(params: Map<String, Any>?) {
        if (params == null) return

        logger.debug("JASPR DAEMON scopes.result received. Files=${params.size}")

        for ((rawPath, data) in params) {
            val file = normalizePath(rawPath)
            val map = data as? Map<String, Any> ?: continue

            val components = (map["components"] as? List<*>)
                ?.filterIsInstance<String>()
                ?: emptyList()

            val serverRoots = parseScopeTargets(map["serverScopeRoots"])
            val clientRoots = parseScopeTargets(map["clientScopeRoots"])

            fileScopes[file] = FileComponentScopes(
                components = components,
                serverScopeRoots = serverRoots,
                clientScopeRoots = clientRoots,
            )
        }

        ApplicationManager.getApplication().invokeLater {
            refreshHintsForOpenFiles()

            val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()

            executor.schedule({
                ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() }
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)

            executor.schedule({
                ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() }
            }, 2, java.util.concurrent.TimeUnit.SECONDS)

            executor.schedule({
                ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() }
            }, 5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseScopeTargets(raw: Any?): List<ScopeTarget> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            val path = map["path"] as? String ?: return@mapNotNull null
            val line = (map["line"] as? Double)?.toInt() ?: return@mapNotNull null
            val character = (map["character"] as? Double)?.toInt() ?: 0
            val url = map["url"] as? String
            ScopeTarget(normalizePath(path), line, character, url)
        }
    }

    private fun normalizePath(raw: String): String = try {
        val file = if (raw.startsWith("file://")) {
            File(URI(raw))
        } else {
            val f = File(raw)
            if (f.isAbsolute) f else File(project.basePath, raw)
        }
        // Normalize path for consistent key lookup
        file.canonicalPath.replace("\\", "/")
    } catch (e: Exception) {
        File(raw).absolutePath.replace("\\", "/")
    }

    fun getScopesForFile(filePath: String): FileComponentScopes? {
        val normalizedPath = normalizePath(filePath)
        val scopes = fileScopes[normalizedPath]
        if (scopes == null) {
            if (fileScopes.isNotEmpty()) {
                logger.debug("JASPR DAEMON: no scopes found for $normalizedPath. Known paths: ${fileScopes.keys.size}")
            }
        }
        return scopes
    }

    override fun dispose() = stop()

    fun stop() {
        processHandler?.let {
            if (!it.isProcessTerminated) it.destroyProcess()
            processHandler = null
        }
    }

    private data class DaemonMessage(
        val id: Int? = null,
        val event: String? = null,
        val params: Map<String, Any>? = null,
        val result: Any? = null,
        val error: Any? = null,
    )

    /**
     * All scope information for a single Dart file, mirroring the VS Code
     * ScopeResults interface in scopes_domain.ts.
     */
    data class FileComponentScopes(
        /** Names of Jaspr component classes found in this file. */
        val components: List<String>,
        /** Locations of server-scope root components (e.g. the @server annotation site). */
        val serverScopeRoots: List<ScopeTarget>,
        /** Locations of client-scope root components (e.g. the @client annotation site). */
        val clientScopeRoots: List<ScopeTarget>,
    )

    /**
     * A navigation target — the definition site of a scope root.
     * Mirrors the VS Code ScopeTarget interface.
     */
    data class ScopeTarget(
        val path: String,
        /** 1-based line number, matching the VS Code plugin convention. */
        val line: Int,
        val character: Int,
        val url: String? = null,
    )
}