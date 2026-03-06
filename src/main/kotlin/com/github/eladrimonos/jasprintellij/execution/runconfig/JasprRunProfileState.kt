package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.execution.JasprDaemonProcessHandler
import com.github.eladrimonos.jasprintellij.startup.JasprDartSdkResolver
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets

class JasprRunProfileState(
    private val environment: ExecutionEnvironment,
    private val options: JasprRunConfigurationOptions,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory
            .getInstance()
            .createBuilder(environment.project)
            .console

        val processHandler = JasprDaemonProcessHandler(
            commandLine = buildCommandLine(),
            project = environment.project,
            onOutput = { text, type -> console.print(text, type) },
            onServerStarted = { vmServiceUri ->
                // TODO: attach a Dart debugger to vmServiceUri
                // (requires the Dart/Flutter IntelliJ plugin API)
            },
            onClientDebugPort = { wsUri ->
                // TODO: attach a Dart debugger to wsUri
            },
        )

        ProcessTerminatedListener.attach(processHandler)
        processHandler.startNotify()

        return DefaultExecutionResult(console, processHandler)
    }

    // -------------------------------------------------------------------------
    // Build the `jaspr daemon` command line
    // -------------------------------------------------------------------------

    private fun buildCommandLine(): GeneralCommandLine {
        val sdkPath = JasprDartSdkResolver.getConfiguredDartSdkHomePath(environment.project)
            ?: error("Dart SDK not configured. Go to Settings → Languages & Frameworks → Dart and set the SDK path.")
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withWorkDirectory(environment.project.basePath)
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