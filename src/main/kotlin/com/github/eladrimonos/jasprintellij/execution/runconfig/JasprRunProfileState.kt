package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.execution.JasprDaemonProcessHandler
import com.github.eladrimonos.jasprintellij.execution.JasprVmServiceDebugProcess
import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTabbedPane
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
import javax.swing.Icon
import javax.swing.JComponent

class JasprRunProfileState(
    private val environment: ExecutionEnvironment,
    private val options: JasprRunConfigurationOptions,
) : RunProfileState {

    private val logger = Logger.getInstance(JasprRunProfileState::class.java)
    private val project get() = environment.project

    /** True when the user clicked Debug instead of Run. */
    private val isDebug get() = environment.executor is DefaultDebugExecutor

    // Two independent consoles — server-side Dart VM, and client-side browser.
    private val serverConsole: ConsoleView = TextConsoleBuilderFactory
        .getInstance().createBuilder(project).console
    private val clientConsole: ConsoleView = TextConsoleBuilderFactory
        .getInstance().createBuilder(project).console
    private val compositeConsole = CompositeConsoleView(serverConsole, clientConsole)

    // Kept as a field so attachClientVmDebugger can reference it.
    private lateinit var processHandler: JasprDaemonProcessHandler

    // -------------------------------------------------------------------------

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        processHandler = JasprDaemonProcessHandler(
            commandLine = buildCommandLine(),
            project = project,

            // Daemon-level and server-side output → server console tab.
            onOutput = { text, type ->
                serverConsole.print(text, type)
            },

            // Client-side output → client console tab.
            onClientOutput = { text, type ->
                clientConsole.print(text, type)
            },

            // ── Server-side Dart VM ──────────────────────────────────────────
            onServerStarted = { vmServiceUri ->
                serverConsole.print(
                    "🔗 Dart VM Service: $vmServiceUri\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                if (isDebug) {
                    val devToolsUrl = buildDevToolsUrl(vmServiceUri)
                    serverConsole.print(
                        "   DevTools: $devToolsUrl\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT,
                    )
                    attachServerVmDebugger(vmServiceUri)
                }
            },

            // ── Client-side browser / JS ─────────────────────────────────────
            //
            // VS Code creates a second separate "dart" debug session here.
            // We mirror that: a second XDebugSession is started in IntelliJ so
            // both server and client appear as independent debug tabs.
            onClientDebugPort = { wsUri ->
                clientConsole.print(
                    "🌐 Client debug WS: $wsUri\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                if (isDebug) {
                    attachClientVmDebugger(wsUri)
                }
            },
        )

        ProcessTerminatedListener.attach(processHandler)
        processHandler.startNotify()

        return if (isDebug) {
            val tabbedComponent = JBTabbedPane()
            tabbedComponent.addTab("Server", serverConsole.component)
            tabbedComponent.addTab("Client",  clientConsole.component)
            compositeConsole.setTabbedComponent(tabbedComponent)
            DefaultExecutionResult(compositeConsole, processHandler)
        } else {
            DefaultExecutionResult(serverConsole, processHandler)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server debugger — Dart VM Service (mirrors VS Code's attachDebugger("Server", …))
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachServerVmDebugger(vmServiceUri: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val wsUri = toWsUri(vmServiceUri)
                val projectVFile = LocalFileSystem.getInstance()
                    .findFileByPath(project.basePath ?: return@invokeLater)
                    ?: return@invokeLater
                val urlResolver = DartUrlResolver.getInstance(project, projectVFile)

                storeVmServiceUri(wsUri)

                XDebuggerManager.getInstance(project).startSession(
                    environment,
                    object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession): XDebugProcess =
                            JasprVmServiceDebugProcess(
                                session               = session,
                                executionResult       = null,
                                vmServiceWsUri        = wsUri,
                                dartUrlResolver       = urlResolver,
                                timeout               = 10_000,
                                currentWorkingDirectory = projectVFile,
                            )
                    },
                )
                logger.info("Server VM debugger session started for $wsUri")
            } catch (e: Exception) {
                logger.warn("Failed to attach server VM debugger: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Client debugger — second independent XDebugSession
    // (mirrors VS Code's attachDebugger("Client", wsUri))
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a second, independent [XDebugSession] for the client-side
     * Dart/JS process.
     *
     * In VS Code this is done by calling `vscode.debug.startDebugging` a
     * second time with `{ type: "dart", request: "attach", vmServiceUri }`.
     * Here we replicate that by building a fresh [ExecutionEnvironment] backed
     * by a lightweight [RunProfile] so IntelliJ treats it as a separate session.
     */
    private fun attachClientVmDebugger(wsUri: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val wsUriNormalized = toWsUri(wsUri)
                val projectVFile = LocalFileSystem.getInstance()
                    .findFileByPath(project.basePath ?: return@invokeLater)
                    ?: return@invokeLater
                val urlResolver = DartUrlResolver.getInstance(project, projectVFile)

                // A thin RunProfile that gives the client session its own name
                // in the Debug tool window, without interfering with the server session.
                val clientProfile = object : RunProfile {
                    override fun getState(e: Executor, env: ExecutionEnvironment) = null
                    override fun getName(): String = "Client | Jaspr"
                    override fun getIcon(): Icon? = null
                }

                val clientEnv = ExecutionEnvironmentBuilder(project, environment.executor)
                    .runProfile(clientProfile)
                    .runner(environment.runner)
                    .build()

                XDebuggerManager.getInstance(project).startSession(
                    clientEnv,
                    object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession): XDebugProcess =
                            JasprVmServiceDebugProcess(
                                session                 = session,
                                executionResult         = null,
                                vmServiceWsUri          = wsUriNormalized,
                                dartUrlResolver         = urlResolver,
                                timeout                 = 10_000,
                                currentWorkingDirectory = projectVFile,
                            )
                    },
                )
                logger.info("Client VM debugger session started for $wsUriNormalized")
            } catch (e: Exception) {
                logger.warn("Failed to attach client VM debugger: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VM service URI helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persists the VM service URI in the run-configuration's user data so that
     * [DartVmServiceDebugProcess] (DebugType.REMOTE) can retrieve it during
     * its `sessionInitialized` callback via the standard Dart plugin key
     * `DartVmServiceDebugProcess.VM_SERVICE_URI_KEY`.
     *
     * Accessed via reflection because the field visibility varies across
     * Dart plugin versions.
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
            logger.debug("VM_SERVICE_URI_KEY not accessible, skipping pre-fill: ${it.message}")
        }
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    // CompositeConsoleView — tabbed view shown in the Run tool window
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A [ConsoleView] that delegates structural calls to [server] and can
     * display a tabbed [JComponent] so both consoles are reachable from the
     * Run tool window.
     *
     * Output is **not** forwarded here anymore — each console receives its own
     * stream via [onOutput] / [onClientOutput] callbacks so the tabs stay clean.
     */
    private class CompositeConsoleView(
        private val server: ConsoleView,
        private val client: ConsoleView,
    ) : ConsoleView {

        private var tabbedComponent: JComponent? = null

        fun setTabbedComponent(component: JComponent) {
            tabbedComponent = component
        }

        // Route print to the server console by default (used for process-level
        // messages such as "Process finished with exit code 0").
        override fun print(text: String, contentType: ConsoleViewContentType) =
            server.print(text, contentType)

        override fun setOutputPaused(p0: Boolean)            = server.setOutputPaused(p0)
        override fun isOutputPaused(): Boolean               = server.isOutputPaused()
        override fun hasDeferredOutput(): Boolean            = server.hasDeferredOutput()
        override fun performWhenNoDeferredOutput(p0: Runnable) = server.performWhenNoDeferredOutput(p0)
        override fun setHelpId(p0: String)                   = server.setHelpId(p0)
        override fun printHyperlink(p0: String, p1: HyperlinkInfo?) = server.printHyperlink(p0, p1)
        override fun getContentSize(): Int                   = server.getContentSize()
        override fun canPause(): Boolean                     = server.canPause()
        override fun createConsoleActions(): Array<out AnAction?> = server.createConsoleActions()
        override fun allowHeavyFilters()                     = server.allowHeavyFilters()
        override fun clear()                                 = server.clear()
        override fun addMessageFilter(filter: Filter)        = server.addMessageFilter(filter)
        override fun getComponent()                          = tabbedComponent ?: server.component
        override fun getPreferredFocusableComponent()        = server.preferredFocusableComponent
        override fun scrollTo(offset: Int)                   = server.scrollTo(offset)
        override fun attachToProcess(handler: ProcessHandler) = server.attachToProcess(handler)

        override fun dispose() {
            server.dispose()
            client.dispose()
        }
    }
}