package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.charset.StandardCharsets

class JasprRunProfileState(
    private val environment: ExecutionEnvironment,
    private val options: JasprRunConfigurationOptions,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val commandLine = buildCommandLine()

        val processHandler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(processHandler)

        val console = executor?.let {
            val consoleView = com.intellij.execution.filters.TextConsoleBuilderFactory
                .getInstance()
                .createBuilder(environment.project)
                .console
            consoleView.attachToProcess(processHandler)
            consoleView
        }

        return DefaultExecutionResult(console, processHandler)
    }

    // -----------------------------------------------------------------------
    // Build the jaspr serve command line
    // -----------------------------------------------------------------------

    private fun buildCommandLine(): GeneralCommandLine {
        // Resolve dart executable from project SDK setting (fall back to PATH)
        val dartExe = resolveDartExecutable()

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withWorkDirectory(environment.project.basePath)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(EnvironmentUtil.getEnvironmentMap())

        cmd.addParameters("pub", "global", "run", "jaspr_cli:jaspr", "serve")

        // -v / --verbose
        if (options.verbose) cmd.addParameter("--verbose")

        // -i / --input
        options.input.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--input", it) }

        // -m / --mode
        options.mode.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--mode", it) }

        // -p / --port
        options.port.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--port", it) }

        // --web-port
        options.webPort.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--web-port", it) }

        // --proxy-port
        options.proxyPort.takeIf { it.isNotBlank() }
            ?.let { cmd.addParameters("--proxy-port", it) }

        // build mode: --debug | --release (omit for default dev mode)
        when (options.buildMode) {
            "debug"   -> cmd.addParameter("--debug")
            "release" -> cmd.addParameter("--release")
        }

        // --experimental-wasm
        if (options.experimentalWasm) cmd.addParameter("--experimental-wasm")

        // --[no-]managed-build-options  (default is on, so only emit when disabled)
        if (!options.managedBuildOptions) cmd.addParameter("--no-managed-build-options")

        // --skip-server
        if (options.skipServer) cmd.addParameter("--skip-server")

        // --launch-in-chrome
        if (options.launchInChrome) cmd.addParameter("--launch-in-chrome")

        // --dart-define
        addMultiValueParam(cmd, options.dartDefine, "--dart-define")

        // --dart-define-client
        addMultiValueParam(cmd, options.dartDefineClient, "--dart-define-client")

        // --dart-define-server
        addMultiValueParam(cmd, options.dartDefineServer, "--dart-define-server")

        // --dart-define-from-file
        addMultiValueParam(cmd, options.dartDefineFromFile, "--dart-define-from-file")

        return cmd
    }

    /** Emit `--flag value` for each non-blank line in a newline-separated field. */
    private fun addMultiValueParam(cmd: GeneralCommandLine, raw: String, flag: String) {
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { cmd.addParameters(flag, it) }
    }

    private fun resolveDartExecutable(): String {
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        // Try to find dart on PATH; callers can extend this to read from plugin settings
        return dartExeName
    }
}