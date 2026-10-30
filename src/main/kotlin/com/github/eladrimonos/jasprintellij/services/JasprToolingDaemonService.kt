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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.EditorNotifications
import com.intellij.util.FileContentUtilCore
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
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
        set(value) {
            val changed = field != value
            field = value
            // The CLI version mismatch banner depends on it (see JasprCliVersionMismatchNotificationProvider).
            if (changed && !project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
        }

    @JasprLegacy("Request ID", "0.23.0")
    private var nextId = 1

    @JasprLegacy("Pending requests map", "0.23.0")
    private val pendingRequests = mutableMapOf<Int, CompletableFuture<Any?>>()
    
    /**
     * Scope data per normalized Dart file path, from the legacy tooling daemon (one app only).
     * Read from background read-action threads (CodeVision) while being replaced from the
     * daemon output thread, so it is an immutable map published through a volatile reference:
     * writers build a complete new map and swap it in with a single assignment, readers read
     * the reference once.
     */
    @JasprLegacy("Tooling daemon scopes", "0.23.0")
    @Volatile
    private var fileScopes: Map<String, FileComponentScopes> = emptyMap()

    /**
     * Scope data from each app's `scopes.json` (jaspr_cli >= 0.23), keyed by the app's
     * normalized directory. Published like [fileScopes]: every change swaps in a new map.
     */
    @Volatile
    private var appScopes: Map<String, AppScopes> = emptyMap()

    /** Normalized directories of the Jaspr apps whose `scopes.json` is watched. */
    @Volatile
    private var appDirs: List<String> = emptyList()

    @JasprLegacy("Output buffer for daemon stream", "0.23.0")
    private val outputBuffer = StringBuilder()

    internal var useFileSystemScopes = false
    private var watchTask: ScheduledFuture<*>? = null

    /**
     * Directory of the actual Jaspr package the daemon/CLI should run against —
     * the project root if it depends on jaspr directly, otherwise the nearest
     * workspace/Melos member package that does. Falls back to [Project.getBasePath]
     * if no jaspr package can be found, so callers always get a usable path.
     */
    private val jasprProjectDir: File
        get() = JasprTooling.findJasprProjectDir(project) ?: File(project.basePath ?: ".")

    val isAlive: Boolean
        get() = useFileSystemScopes || (processHandler?.isProcessTerminated == false)

    /**
     * Starts scope tooling for the installed jaspr_cli. Pass [knownCliVersion] when the
     * caller has just read it (e.g. from [JasprTooling.preflightCheck]) to avoid
     * spawning `jaspr --version` again.
     */
    fun start(knownCliVersion: String? = null) {
        if (isAlive) return

        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return
        val tooling = JasprTooling()

        cliVersion = knownCliVersion ?: tooling.readJasprCliVersion(sdkPath)
        logger.info("Jaspr Tooling: cliVersion=$cliVersion")

        if (tooling.isVersionAtLeast(cliVersion, "0.23.0")) {
            logger.info("Jaspr Tooling: using file-system scopes for version >= 0.23.0")
            useFileSystemScopes = true
            setupScopesFileWatcher()
            return
        }

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val jasprDir = jasprProjectDir
        if (!jasprDir.isDirectory) {
            logger.warn("Jaspr Tooling: Jaspr package directory is missing or invalid ($jasprDir). Cannot start daemon.")
            return
        }

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "tooling-daemon")
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(jasprDir)


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
                    fileScopes = emptyMap()
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
        refreshApps()

        // .dart_tool is excluded from the VFS, so poll each app's scopes.json mtime on the
        // shared scheduler instead of relying on VFS events. Cancelled in stop().
        watchTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::pollScopesFiles, SCOPES_POLL_INTERVAL_SECONDS, SCOPES_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS,
        )
    }

    /**
     * Re-discovers the project's Jaspr apps (e.g. after a pubspec.yaml or melos.yaml change),
     * drops scope data of apps that are gone and loads the ones that are new.
     */
    @Synchronized
    fun refreshApps(discovered: List<File> = JasprTooling.findJasprProjectDirs(project)) {
        if (!useFileSystemScopes) return
        val dirs = discovered
            .ifEmpty { listOf(jasprProjectDir) }
            .map { normalizePath(it.path) }
        if (dirs == appDirs) return
        appDirs = dirs
        logger.info("Jaspr Tooling: watching scopes.json of ${dirs.size} app(s): $dirs")

        val removed = appScopes.keys - dirs.toSet()
        if (removed.isNotEmpty()) {
            val previous = appScopes
            appScopes = previous - removed
            refreshHintsForOpenFiles(removed.flatMapTo(HashSet()) { previous.getValue(it).files.keys })
        }
        pollScopesFiles()
    }

    @Synchronized
    internal fun pollScopesFiles() {
        for (dir in appDirs) {
            val scopesFile = File(dir, SCOPES_FILE)
            val loadedAt = appScopes[dir]?.lastModified
            if (scopesFile.exists()) {
                if (loadedAt == null || scopesFile.lastModified() > loadedAt) loadScopesFromFile(scopesFile, File(dir))
            } else if (loadedAt != null) {
                val previous = appScopes.getValue(dir)
                appScopes = appScopes - dir
                refreshHintsForOpenFiles(previous.files.keys)
            }
        }
    }

    /**
     * Loads [file], the `scopes.json` of the app at [appDir], replacing that app's previous
     * scope data. Relative paths in it are resolved against [appDir].
     */
    @Synchronized
    internal fun loadScopesFromFile(file: File, appDir: File = jasprProjectDir) {
        val app = normalizePath(appDir.path)
        try {
            val lastModified = file.lastModified()
            val content = file.readText(StandardCharsets.UTF_8)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(content, type)

            val locationsRaw = data["locations"] as? Map<String, Any> ?: emptyMap()
            val scopesRaw = data["scopes"] as? Map<String, Any> ?: emptyMap()

            val locations = locationsRaw.mapValues { (_, value) ->
                val map = value as? Map<String, Any> ?: emptyMap()
                val path = map["path"] as? String ?: ""
                ScopeLocation(
                    path = normalizePath(path, appDir),
                    name = map["name"] as? String ?: "",
                    line = (map["line"] as? Double)?.toInt() ?: 0,
                    char = (map["char"] as? Double)?.toInt() ?: 0,
                    length = (map["length"] as? Double)?.toInt() ?: 0
                )
            }

            // Build the complete map locally and publish it in one assignment, so readers never
            // observe an empty or partially filled map mid-reload.
            val newScopes = LinkedHashMap<String, FileComponentScopes>()

            for ((rawPath, scopeData) in scopesRaw) {
                val filePath = normalizePath(rawPath, appDir)
                val map = scopeData as? Map<String, Any> ?: continue

                val componentIds = (map["components"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val serverRootIds = (map["serverScopeRoots"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val clientRootIds = (map["clientScopeRoots"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                val componentNames = componentIds.mapNotNull { locations[it]?.name }
                val serverTargets = serverRootIds.mapNotNull { locations[it]?.toScopeTarget() }
                val clientTargets = clientRootIds.mapNotNull { locations[it]?.toScopeTarget() }

                newScopes[filePath] = FileComponentScopes(
                    components = componentNames,
                    serverScopeRoots = serverTargets,
                    clientScopeRoots = clientTargets
                )
            }

            val previousScopes = appScopes[app]?.files.orEmpty()
            appScopes = appScopes + (app to AppScopes(app, File(app).name, lastModified, newScopes))

            logger.info(
                "Jaspr Tooling: loaded $app/$SCOPES_FILE — files=${newScopes.size}, " +
                    "components=${newScopes.values.sumOf { it.components.size }}"
            )
            if (newScopes.isNotEmpty()) {
                logger.info("Jaspr Tooling: known scope file paths: ${newScopes.keys.take(10)}")
            }

            // scopes.json is rewritten on every rebuild; only touch editors whose scopes changed.
            val changed = changedScopePaths(previousScopes, newScopes)
            if (changed.isNotEmpty()) refreshHintsForOpenFiles(changed)
        } catch (e: Exception) {
            logger.warn("Failed to load scopes from file: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // HTML conversion
    // -------------------------------------------------------------------------

    /**
     * Converts an HTML fragment (e.g. the contents of `<body>`) to Jaspr component code.
     * Several top-level nodes come back as a `.fragment([...])` expression.
     */
    fun convertHtml(html: String): String? {
        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project) ?: return null
        val tooling = JasprTooling()

        if (tooling.isVersionAtLeast(cliVersion, "0.23.0")) {
            return convertHtmlViaCli(sdkPath, html)
        }

        if (!isAlive) return null

        val id = nextId++
        val future = CompletableFuture<Any?>()
        pendingRequests[id] = future

        sendCommand(mapOf(
            "id"     to id,
            "method" to "html.convert",
            // The legacy daemon converts parseFragment(html).firstChild only, so wrap
            // everything in a single root element.
            "params" to mapOf("html" to "<div>$html</div>"),
        ))

        return try {
            future.get(10, TimeUnit.SECONDS) as? String
        } catch (e: Exception) {
            logger.warn("html.convert failed or timed out: ${e.message}")
            pendingRequests.remove(id)
            null
        }
    }

    private fun convertHtmlViaCli(sdkPath: String, html: String): String? {
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        // Pass the HTML through a temp file rather than `--html <json>`: large pages
        // would exceed the command-line length limit (32 KB on Windows).
        val input = File.createTempFile("jaspr_convert_", ".html")
        try {
            input.writeText(html, StandardCharsets.UTF_8)

            val cmd = GeneralCommandLine()
                .withExePath(dartExe)
                .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "convert-html", "--json", "--file", input.absolutePath)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(jasprProjectDir)

            val out = DefaultCliRunner.run(cmd)
            if (out.exitCode != 0) {
                logger.warn("Jaspr CLI convert-html failed: ${out.stderr}\n${out.stdout}")
                return null
            }
            return parseConvertHtmlOutput(out.stdout)
        } finally {
            input.delete()
        }
    }

    /**
     * Extracts the converted code from `convert-html --json` output. jaspr_cli may
     * print other text first (e.g. an "Update available!" box), so only the JSON
     * object line is used.
     */
    internal fun parseConvertHtmlOutput(stdout: String): String? {
        val jsonLine = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: return null
        return runCatching {
            gson.fromJson(jsonLine, Map::class.java)?.get("result") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
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
        sendCommand(mapOf(
            "id"     to nextId++,
            "method" to "scopes.register",
            "params" to mapOf("folders" to listOf(jasprProjectDir.absolutePath)),
        ))
    }

    // -------------------------------------------------------------------------
    // Scopes
    // -------------------------------------------------------------------------

    /**
     * Scope data for [filePath]. A file inside an app only gets that app's data, even if
     * another app's `scopes.json` mentions it. A file of a shared package gets the data of
     * every app that renders it, with each root tagged by its app so none is misattributed.
     */
    fun getScopesForFile(filePath: String): FileComponentScopes? {
        val normalizedPath = normalizePath(filePath)
        val apps = appScopes
        if (apps.isEmpty()) return fileScopes[normalizedPath]

        val owner = appDirs.filter { normalizedPath.startsWith("$it/") }.maxByOrNull { it.length }
        if (owner != null) return apps[owner]?.files?.get(normalizedPath)

        val hits = apps.values.mapNotNull { app -> app.files[normalizedPath]?.let { app to it } }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 && appDirs.size <= 1 -> hits.single().second
            else -> FileComponentScopes(
                components = hits.flatMap { it.second.components }.distinct(),
                serverScopeRoots = hits.flatMap { (app, scopes) -> scopes.serverScopeRoots.map { it.copy(app = app.name) } },
                clientScopeRoots = hits.flatMap { (app, scopes) -> scopes.clientScopeRoots.map { it.copy(app = app.name) } },
            )
        }
    }

    /**
     * Refreshes CodeVision for open Dart editors. With [changedPaths] (normalized, as
     * produced by [normalizePath]) only those files are refreshed; null means all.
     */
    fun refreshHintsForOpenFiles(changedPaths: Set<String>? = null) {
        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val openDartFiles = FileEditorManager.getInstance(project).openFiles
                .filter { it.extension == "dart" }
                .filter { changedPaths == null || normalizePath(it.path) in changedPaths }
            if (openDartFiles.isEmpty()) return@invokeLater

            DaemonCodeAnalyzer.getInstance(project).restart()

            // DaemonCodeAnalyzer.restart() alone is not enough for already-open editors:
            // CodeVision keeps a per-document result cache that a plain daemon restart
            // does not invalidate, so tabs opened before scope data was ready would
            // otherwise keep showing no CodeVision until manually closed and reopened.
            // Force a real reparse of those open Dart files so CodeVision recomputes.
            FileContentUtilCore.reparseFiles(openDartFiles)
        }
    }

    /** Paths whose scope data differs between [old] and [new] (added, removed or changed). */
    internal fun changedScopePaths(
        old: Map<String, FileComponentScopes>,
        new: Map<String, FileComponentScopes>,
    ): Set<String> = (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] }

    @Suppress("UNCHECKED_CAST")
    private fun handleScopesResultEvent(params: Map<String, Any>?) {
        if (params == null) return
        logger.debug("JASPR DAEMON scopes.result received. Files=${params.size}")

        val newEntries = LinkedHashMap<String, FileComponentScopes>()
        for ((rawPath, data) in params) {
            val file = normalizePath(rawPath)
            val map = data as? Map<String, Any> ?: continue
            newEntries[file] = FileComponentScopes(
                components      = (map["components"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                serverScopeRoots = parseScopeTargets(map["serverScopeRoots"]),
                clientScopeRoots = parseScopeTargets(map["clientScopeRoots"]),
            )
        }
        // Copy-on-write merge. Only the daemon output thread writes incrementally, so a plain
        // read-modify-write of the volatile reference is sufficient here.
        fileScopes = fileScopes + newEntries

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

    private fun normalizePath(raw: String, baseDir: File = jasprProjectDir): String = try {
        val file = if (raw.startsWith("file://")) File(URI(raw))
        else File(raw).let { if (it.isAbsolute) it else File(baseDir, raw) }
        file.canonicalPath.replace("\\", "/")
    } catch (e: Exception) {
        File(raw).absolutePath.replace("\\", "/")
    }

    override fun dispose() = stop()

    /** Re-reads the jaspr_cli version and restarts scope tooling, e.g. after a CLI update. */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        processHandler?.let {
            if (!it.isProcessTerminated) it.destroyProcess()
            processHandler = null
        }
        watchTask?.cancel(false)
        watchTask = null
        
        // Reset state for clean restarts (important for tests and SDK changes)
        useFileSystemScopes = false
        cliVersion = null
        daemonVersion = null
        daemonPid = null
        fileScopes = emptyMap()
        appScopes = emptyMap()
        appDirs = emptyList()
        synchronized(outputBuffer) { outputBuffer.setLength(0) }
    }

    companion object {
        private const val SCOPES_POLL_INTERVAL_SECONDS = 2L
        private const val SCOPES_FILE = ".dart_tool/jaspr/scopes.json"
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
        /** Name of the app rendering this root, set only for files shared by several apps. */
        val app: String? = null,
    )

    /** The scope data loaded from one app's `scopes.json`. */
    private data class AppScopes(
        val dir: String,
        val name: String,
        val lastModified: Long,
        val files: Map<String, FileComponentScopes>,
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
