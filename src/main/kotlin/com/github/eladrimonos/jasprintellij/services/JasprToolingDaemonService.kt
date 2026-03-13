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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class JasprToolingDaemonService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(JasprToolingDaemonService::class.java)
    private var processHandler: KillableColoredProcessHandler? = null
    private val gson = Gson()

    var daemonVersion: String? = null
    var daemonPid: Long? = null

    private var nextId = 1
    private val pendingRequests = mutableMapOf<Int, CompletableFuture<Any?>>()
    private val fileScopes = mutableMapOf<String, FileComponentScopes>()
    private val outputBuffer = StringBuilder()

    val isAlive: Boolean
        get() = processHandler?.isProcessTerminated == false

    fun start() {
        if (isAlive) return

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
                    pendingRequests.values.forEach { it.cancel(true) }
                    pendingRequests.clear()
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

    // -------------------------------------------------------------------------
    // HTML conversion
    // -------------------------------------------------------------------------

    fun convertHtml(html: String): String? {
        if (!isAlive) return null

        val id = nextId++
        val future = CompletableFuture<Any?>()
        pendingRequests[id] = future

        sendCommand(mapOf(
            "id"     to id,
            "method" to "html.convert",
            "params" to mapOf("html" to html),
        ))

        return try {
            future.get(10, TimeUnit.SECONDS) as? String
        } catch (e: Exception) {
            logger.warn("html.convert failed or timed out: ${e.message}")
            pendingRequests.remove(id)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    private fun processDaemonMessage(text: String) {
        try {
            if (text.startsWith("[")) {
                val listType = object : TypeToken<List<DaemonMessage>>() {}.type
                val messages: List<DaemonMessage> = gson.fromJson(text, listType)
                for (message in messages) handleMessage(message)
            } else if (text.startsWith("{")) {
                val message: DaemonMessage = gson.fromJson(text, DaemonMessage::class.java)
                handleMessage(message)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse daemon message: $text", e)
        }
    }

    private fun handleMessage(message: DaemonMessage) {
        // Response to a pending request (has id, no event)
        if (message.id != null && message.event == null) {
            pendingRequests.remove(message.id)?.complete(message.result)
            return
        }

        when (message.event) {
            "daemon.connected" -> handleConnectedEvent(message.params)
            "daemon.log"       -> logger.debug("JASPR DAEMON log: ${message.params?.get("message")}")
            "scopes.result"    -> handleScopesResultEvent(message.params)
            "scopes.status"    -> logger.debug("JASPR DAEMON status: ${message.params}")
            else               -> logger.debug("Unknown daemon event: ${message.event}")
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
        sendCommand(mapOf(
            "id"     to nextId++,
            "method" to "scopes.register",
            "params" to mapOf("folders" to listOf(basePath)),
        ))
    }

    // -------------------------------------------------------------------------
    // Scopes
    // -------------------------------------------------------------------------

    fun getScopesForFile(filePath: String): FileComponentScopes? {
        val normalizedPath = normalizePath(filePath)
        val scopes = fileScopes[normalizedPath]
        if (scopes == null && fileScopes.isNotEmpty()) {
            logger.debug("JASPR DAEMON: no scopes found for $normalizedPath. Known paths: ${fileScopes.keys.size}")
        }
        return scopes
    }

    fun refreshHintsForOpenFiles() {
        if (project.isDisposed) return
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val analyzer = DaemonCodeAnalyzer.getInstance(project)

        val openFiles = fileEditorManager.openFiles.filter { it.extension == "dart" }
        if (openFiles.isEmpty()) return

        for (vFile in openFiles) {
            ApplicationManager.getApplication().runReadAction {
                val psiFile = psiManager.findFile(vFile)
                if (psiFile != null && psiFile.isValid) analyzer.restart(psiFile)
            }
        }

        try {
            com.intellij.util.FileContentUtil.reparseFiles(project, openFiles, true)
        } catch (e: Exception) {
            logger.warn("JASPR DAEMON: FileContentUtil.reparseFiles failed: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleScopesResultEvent(params: Map<String, Any>?) {
        if (params == null) return
        logger.debug("JASPR DAEMON scopes.result received. Files=${params.size}")

        for ((rawPath, data) in params) {
            val file = normalizePath(rawPath)
            val map = data as? Map<String, Any> ?: continue
            fileScopes[file] = FileComponentScopes(
                components      = (map["components"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                serverScopeRoots = parseScopeTargets(map["serverScopeRoots"]),
                clientScopeRoots = parseScopeTargets(map["clientScopeRoots"]),
            )
        }

        ApplicationManager.getApplication().invokeLater {
            refreshHintsForOpenFiles()
            val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            executor.schedule({ ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() } }, 500, TimeUnit.MILLISECONDS)
            executor.schedule({ ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() } }, 2, TimeUnit.SECONDS)
            executor.schedule({ ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() } }, 5, TimeUnit.SECONDS)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseScopeTargets(raw: Any?): List<ScopeTarget> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            val path = map["path"] as? String ?: return@mapNotNull null
            val line = (map["line"] as? Double)?.toInt() ?: return@mapNotNull null
            ScopeTarget(
                path      = normalizePath(path),
                line      = line,
                character = (map["character"] as? Double)?.toInt() ?: 0,
                url       = map["url"] as? String,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun sendCommand(command: Any) {
        val json = gson.toJson(listOf(command))
        logger.debug("Sending command to Jaspr Daemon: $json")
        processHandler?.let {
            if (!it.isProcessTerminated) {
                it.processInput.apply {
                    write(json.toByteArray(StandardCharsets.UTF_8))
                    write("\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
            }
        }
    }

    private fun normalizePath(raw: String): String = try {
        val file = if (raw.startsWith("file://")) File(URI(raw))
        else File(raw).let { if (it.isAbsolute) it else File(project.basePath, raw) }
        file.canonicalPath.replace("\\", "/")
    } catch (e: Exception) {
        File(raw).absolutePath.replace("\\", "/")
    }

    override fun dispose() = stop()

    fun stop() {
        processHandler?.let {
            if (!it.isProcessTerminated) it.destroyProcess()
            processHandler = null
        }
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class DaemonMessage(
        val id: Int? = null,
        val event: String? = null,
        val params: Map<String, Any>? = null,
        val result: Any? = null,
        val error: Any? = null,
    )

    /**
     * All scope information for a single Dart file.
     */
    data class FileComponentScopes(
        val components: List<String>,
        val serverScopeRoots: List<ScopeTarget>,
        val clientScopeRoots: List<ScopeTarget>,
    )

    /**
     * A navigation target — the definition site of a scope root.
     */
    data class ScopeTarget(
        val path: String,
        /** 1-based line number. */
        val line: Int,
        val character: Int,
        val url: String? = null,
    )
}