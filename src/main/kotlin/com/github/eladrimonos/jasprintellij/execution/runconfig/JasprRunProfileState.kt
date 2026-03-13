package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.execution.JasprDaemonProcessHandler
import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.EnvironmentUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.lang.dart.ide.runner.server.vmService.DartVmServiceDebugProcess
import com.jetbrains.lang.dart.util.DartUrlResolver
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JasprRunProfileState(
    private val environment: ExecutionEnvironment,
    private val options: JasprRunConfigurationOptions,
) : RunProfileState {

    private val logger = Logger.getInstance(JasprRunProfileState::class.java)
    private val project get() = environment.project

    /** True when the user clicked Debug instead of Run. */
    private val isDebug get() = environment.executor is DefaultDebugExecutor

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory
            .getInstance()
            .createBuilder(project)
            .console

        val processHandler = JasprDaemonProcessHandler(
            commandLine = buildCommandLine(),
            project = project,
            onOutput = { text, type -> console.print(text, type) },

            // TODO Create an IDE dev tools panel

            // ── Server-side Dart VM ───────────────────────────────────────────
            // Fired when jaspr emits `server.started` with the VM Service URI.
            // In debug mode we attach DartVmServiceDebugProcess; otherwise we
            // just print the DevTools link so the developer can open it manually.
            onServerStarted = { vmServiceUri ->
                if (isDebug) {
                    val devToolsUrl = buildDevToolsUrl(vmServiceUri)
                    console.print(
                        "🔗 Dart VM Service: $vmServiceUri\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT,
                    )
                    console.print(
                        "   DevTools: $devToolsUrl\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT,
                    )
                    attachDartVmDebugger(vmServiceUri)
                }
            },

            // ── Client-side browser / JS ──────────────────────────────────────
            // Fired when jaspr emits `client.debugPort` with a Chrome DevTools
            // WebSocket URI (ws://host:port/path/ws).  We open the Dart DevTools
            // page in the system browser so the developer can inspect client code.
            onClientDebugPort = { wsUri ->
                if (isDebug) {
                    console.print(
                        "🌐 Client debug WS: $wsUri\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT,
                    )
                    openClientDevTools(wsUri)
                }
            },
        )

        ProcessTerminatedListener.attach(processHandler)
        processHandler.startNotify()

        return DefaultExecutionResult(console, processHandler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server debugger — Dart VM Service
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches [DartVmServiceDebugProcess] to the already-running Dart VM.
     *
     * Constructor signature (Dart plugin ≥ 2024.x):
     *   DartVmServiceDebugProcess(session, executionResult, dartUrlResolver,
     *     dasExecutionContextId, debugType, timeout, currentWorkingDirectory)
     *
     * We use [DartVmServiceDebugProcess.DebugType.REMOTE] so the process
     * connects to an already-running VM rather than launching a new one.
     * The VM service URI must be stored in the environment's run-profile
     * data *before* the session starts — see [storeVmServiceUri].
     */
    private fun attachDartVmDebugger(vmServiceUri: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val wsUri = toWsUri(vmServiceUri)

                val projectVFile = LocalFileSystem.getInstance()
                    .findFileByPath(project.basePath ?: return@invokeLater)
                    ?: return@invokeLater

                val urlResolver = DartUrlResolver.getInstance(project, projectVFile)

                // Store the URI so DartVmServiceDebugProcess can read it via
                // the standard Dart plugin infrastructure when it initialises.
                storeVmServiceUri(wsUri)

                XDebuggerManager.getInstance(project).startSession(
                    environment,
                    object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession): XDebugProcess =
                            DartVmServiceDebugProcess(
                                /* session                 */ session,
                                /* executionResult         */ null,   // lifecycle owned by JasprDaemonProcessHandler
                                /* dartUrlResolver         */ urlResolver,
                                /* dasExecutionContextId   */ null,
                                /* debugType               */ DartVmServiceDebugProcess.DebugType.REMOTE,
                                /* timeout                 */ 10_000,
                                /* currentWorkingDirectory */ projectVFile,
                            )
                    },
                )
                logger.info("Dart VM debugger session started for $wsUri")
            } catch (e: Exception) {
                logger.warn("Failed to attach Dart VM debugger: ${e.message}", e)
            }
        }
    }

    /**
     * Persists the VM service URI in the run-configuration's user data so that
     * [DartVmServiceDebugProcess] (DebugType.REMOTE) can retrieve it during
     * its `sessionInitialized` callback via the standard Dart plugin key
     * `DartVmServiceDebugProcess.VM_SERVICE_URI_KEY`.
     *
     * If that key is not accessible (it may be internal/package-private in
     * some plugin versions) the process will fall back to asking the user,
     * which is still functional — just less seamless.
     */
    private fun storeVmServiceUri(wsUri: String) {
        runCatching {
            val keyField = DartVmServiceDebugProcess::class.java
                .getDeclaredField("VM_SERVICE_URI_KEY")
                .also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val key = keyField.get(null) as? com.intellij.openapi.util.Key<String> ?: return
            environment.putUserData(key, wsUri)
            logger.debug("Stored VM service URI in environment user data: $wsUri")
        }.onFailure {
            // Key not accessible in this plugin version — not fatal.
            logger.debug("VM_SERVICE_URI_KEY not accessible, skipping pre-fill: ${it.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Client debugger — open DevTools in the system browser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the Dart DevTools page for the client-side (browser) Dart/JS code.
     *
     * [wsUri] is the raw Chrome DevTools WebSocket URI emitted by jaspr's
     * `client.debugPort` event, e.g.:
     *   `ws://127.0.0.1:8181/TOKEN=/ws`
     */
    private fun openClientDevTools(wsUri: String) {
        val devToolsUrl = buildDevToolsUrl(wsUri)
        ApplicationManager.getApplication().invokeLater {
            try {
                BrowserLauncher.instance.open(devToolsUrl)
                logger.info("Opened client DevTools: $devToolsUrl")
            } catch (e: Exception) {
                logger.warn("Failed to open client DevTools: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URI helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Normalises any VM Service URI variant to a `ws://` WebSocket URI.
     *
     * Jaspr may emit:
     *   - `ws://host:port/token=/ws`   → already correct
     *   - `http://host:port/token=/`   → convert scheme + append /ws
     */
    private fun toWsUri(uri: String): String = when {
        uri.startsWith("http://")  -> uri.replace("http://",  "ws://").trimEnd('/') + "/ws"
        uri.startsWith("https://") -> uri.replace("https://", "wss://").trimEnd('/') + "/ws"
        else -> uri
    }

    /**
     * Builds the Dart DevTools HTML page URL from a VM Service URI.
     *
     * Input:  `ws://127.0.0.1:8181/TOKEN=/ws`
     * Output: `http://127.0.0.1:8181/TOKEN=/devtools/?uri=<encoded-ws-uri>`
     */
    private fun buildDevToolsUrl(rawUri: String): String {
        val wsUri = toWsUri(rawUri)
        return runCatching {
            val parsed = URI(wsUri)
            val base = "http://${parsed.host}:${parsed.port}"
            val tokenPath = parsed.path.removeSuffix("/ws")
            val encodedWs = URLEncoder.encode(wsUri, "UTF-8")
            "$base$tokenPath/devtools/?uri=$encodedWs"
        }.getOrElse {
            logger.warn("Could not build DevTools URL from $rawUri: ${it.message}")
            rawUri
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build the `jaspr daemon` command line
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCommandLine(): GeneralCommandLine {
        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(project)
            ?: error("Dart SDK not configured. Go to Settings → Languages & Frameworks → Dart and set the SDK path.")
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withWorkDirectory(project.basePath)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(EnvironmentUtil.getEnvironmentMap())

        cmd.addParameters("pub", "global", "run", "jaspr_cli:jaspr", "daemon")

        if (options.verbose) cmd.addParameter("--verbose")

        options.input.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--input", it) }

        options.mode.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--mode", it) }

        options.port.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--port", it) }

        options.webPort.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--web-port", it) }

        options.proxyPort.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--proxy-port", it) }

        when (options.buildMode) {
            "debug"   -> cmd.addParameter("--debug")
            "release" -> cmd.addParameter("--release")
        }

        if (options.experimentalWasm) cmd.addParameter("--experimental-wasm")
        if (!options.managedBuildOptions) cmd.addParameter("--no-managed-build-options")
        if (options.skipServer) cmd.addParameter("--skip-server")

        addMultiValueParam(cmd, options.dartDefine, "--dart-define")
        addMultiValueParam(cmd, options.dartDefineClient, "--dart-define-client")
        addMultiValueParam(cmd, options.dartDefineServer, "--dart-define-server")
        addMultiValueParam(cmd, options.dartDefineFromFile, "--dart-define-from-file")

        return cmd
    }

    private fun addMultiValueParam(cmd: GeneralCommandLine, raw: String, flag: String) {
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            .forEach { cmd.addParameters(flag, it) }
    }
}