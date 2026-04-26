package com.github.eladrimonos.jasprintellij.services

import com.github.eladrimonos.jasprintellij.JasprLegacy
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
    private val gson = Gson()

    @JasprLegacy("Daemon process handler", "0.23.0")
    private var processHandler: KillableColoredProcessHandler? = null

    @JasprLegacy("Daemon version", "0.23.0")
    var daemonVersion: String? = null

    @JasprLegacy("Daemon PID", "0.23.0")
    var daemonPid: Long? = null

    var cliVersion: String? = null

    @JasprLegacy("Request ID", "0.23.0")
    private var nextId = 1

    @JasprLegacy("Pending requests map", "0.23.0")
    private val pendingRequests = mutableMapOf<Int, CompletableFuture<Any?>>()
    
    private val fileScopes = mutableMapOf<String, FileComponentScopes>()

    @JasprLegacy("Output buffer for daemon stream", "0.23.0")
    private val outputBuffer = StringBuilder()

    internal var useFileSystemScopes = false
    private var watchThread: Thread? = null
    private var scopesFileLastModified: Long = 0

    val isAlive: Boolean
        get() = useFileSystemScopes || (processHandler?.isProcessTerminated == false)

    fun start() {
        if (isAlive) return

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return
        val tooling = JasprTooling()

        cliVersion = tooling.readJasprCliVersion(sdkPath)
        logger.info("Jaspr Tooling: cliVersion=$cliVersion")

        if (tooling.isVersionAtLeast(cliVersion, "0.23.0")) {
            logger.info("Jaspr Tooling: using file-system scopes for version >= 0.23.0")
            useFileSystemScopes = true
            setupScopesFileWatcher()
            return
        }

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val basePath = project.basePath
        if (basePath == null || !File(basePath).isDirectory) {
            logger.warn("Jaspr Tooling: project base path is missing or invalid ($basePath). Cannot start daemon.")
            return
        }

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "tooling-daemon")
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(basePath)


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

    private fun setupScopesFileWatcher() {
        val basePath = project.basePath ?: return
        val scopesFile = File(basePath, ".dart_tool/jaspr/scopes.json")
        
        // Initial load
        if (scopesFile.exists()) {
            scopesFileLastModified = scopesFile.lastModified()
            loadScopesFromFile(scopesFile)
        }

        watchThread = kotlin.concurrent.thread(isDaemon = true, name = "JasprScopesWatcher") {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(2000)
                    if (scopesFile.exists()) {
                        val currentModified = scopesFile.lastModified()
                        if (currentModified > scopesFileLastModified) {
                            scopesFileLastModified = currentModified
                            loadScopesFromFile(scopesFile)
                        }
                    } else {
                        if (scopesFileLastModified > 0) {
                            scopesFileLastModified = 0
                            fileScopes.clear()
                            ApplicationManager.getApplication().invokeLater { refreshHintsForOpenFiles() }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, exit gracefully
            }
        }
    }

    private fun loadScopesFromFile(file: File) {
        try {
            val content = file.readText(StandardCharsets.UTF_8)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(content, type)

            val locationsRaw = data["locations"] as? Map<String, Any> ?: emptyMap()
            val scopesRaw = data["scopes"] as? Map<String, Any> ?: emptyMap()

            val locations = locationsRaw.mapValues { (_, value) ->
                val map = value as? Map<String, Any> ?: emptyMap()
                val path = map["path"] as? String ?: ""
                ScopeLocation(
                    path = normalizePath(path),
                    name = map["name"] as? String ?: "",
                    line = (map["line"] as? Double)?.toInt() ?: 0,
                    char = (map["char"] as? Double)?.toInt() ?: 0,
                    length = (map["length"] as? Double)?.toInt() ?: 0
                )
            }

            fileScopes.clear()

            for ((rawPath, scopeData) in scopesRaw) {
                val filePath = normalizePath(rawPath)
                val map = scopeData as? Map<String, Any> ?: continue

                val componentIds = (map["components"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val serverRootIds = (map["serverScopeRoots"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val clientRootIds = (map["clientScopeRoots"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                val componentNames = componentIds.mapNotNull { locations[it]?.name }
                val serverTargets = serverRootIds.mapNotNull { locations[it]?.toScopeTarget() }
                val clientTargets = clientRootIds.mapNotNull { locations[it]?.toScopeTarget() }

                fileScopes[filePath] = FileComponentScopes(
                    components = componentNames,
                    serverScopeRoots = serverTargets,
                    clientScopeRoots = clientTargets
                )
            }

            ApplicationManager.getApplication().invokeLater {
                refreshHintsForOpenFiles()
            }
        } catch (e: Exception) {
            logger.warn("Failed to load scopes from file: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // HTML conversion
    // -------------------------------------------------------------------------

    fun convertHtml(html: String, filePath: String? = null): String? {
        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return null
        val tooling = JasprTooling()
        
        if (tooling.isVersionAtLeast(cliVersion, "0.23.0")) {
            return convertHtmlViaCli(sdkPath, html, filePath)
        }

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

    private fun convertHtmlViaCli(sdkPath: String, html: String, filePath: String?): String? {
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val params = mutableListOf("pub", "global", "run", "jaspr_cli:jaspr", "convert-html")
        if (filePath != null) {
            params.add("--file")
            params.add(filePath)
        } else {
            params.add("--html")
            // The CLI expects the HTML string to be JSON-encoded
            params.add(gson.toJson(html))
        }

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withParameters(params)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath)

        val out = DefaultCliRunner.run(cmd)
        if (out.exitCode != 0) {
            logger.warn("Jaspr CLI convert-html failed: ${out.stderr}")
            return null
        }
        return out.stdout.trim()
    }

    // -------------------------------------------------------------------------
    // Message handling (Legacy)
    // -------------------------------------------------------------------------

    @JasprLegacy("Legacy daemon message processing", "0.23.0")
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

    @JasprLegacy("Legacy daemon message handling", "0.23.0")
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

    @JasprLegacy("Legacy daemon connected event handler", "0.23.0")
    private fun handleConnectedEvent(params: Map<String, Any>?) {
        daemonVersion = params?.get("version") as? String
        daemonPid = (params?.get("pid") as? Double)?.toLong()
        logger.warn("JASPR DAEMON connected: version=$daemonVersion, pid=$daemonPid")
        sendRegisterCommand()
    }

    @JasprLegacy("Legacy daemon scope registration", "0.23.0")
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
        
        // Lightweight restart of the Daemon analyzer to trigger CodeVision updates
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart()
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

    @JasprLegacy("Legacy daemon command transmission", "0.23.0")
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
        watchThread?.interrupt()
        watchThread = null
        
        // Reset state for clean restarts (important for tests and SDK changes)
        useFileSystemScopes = false
        cliVersion = null
        daemonVersion = null
        daemonPid = null
        fileScopes.clear()
        synchronized(outputBuffer) { outputBuffer.setLength(0) }
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    @JasprLegacy("Legacy daemon message format", "0.23.0")
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

    data class ScopeLocation(
        val path: String,
        val name: String,
        val line: Int,
        val char: Int,
        val length: Int
    ) {
        fun toScopeTarget(): ScopeTarget {
            return ScopeTarget(
                path = path,
                line = line,
                character = char
            )
        }
    }
}
