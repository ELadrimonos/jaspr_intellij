package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.services.JasprTooling

/**
 * Builds the `jaspr daemon` flags derived from [JasprRunConfigurationOptions],
 * adapting them to the installed jaspr_cli version.
 *
 * jaspr_cli 0.24.0 replaced `--mode reload|refresh` with `--auto reload|restart|refresh`
 * and removed `--web-port` / `--proxy-port` (ports are now picked automatically).
 */
internal object JasprDaemonFlags {

    const val AUTO_ACTION_VERSION = "0.24.0"

    /** Values accepted by the legacy `--mode` option (jaspr_cli < 0.24.0). */
    private val legacyModes = setOf("reload", "refresh")

    /** Values accepted by `--auto` (jaspr_cli >= 0.24.0). */
    private val autoActions = setOf("reload", "restart", "refresh")

    fun supportsAutoAction(cliVersion: String?): Boolean =
        JasprTooling().isVersionAtLeast(cliVersion, AUTO_ACTION_VERSION)

    /** True when the options set port flags that [cliVersion] no longer accepts. */
    fun hasIgnoredPortOptions(options: JasprRunConfigurationOptions, cliVersion: String?): Boolean =
        supportsAutoAction(cliVersion) && (options.webPort.isNotBlank() || options.proxyPort.isNotBlank())

    /**
     * @param cliVersion the installed jaspr_cli version, or null when unknown
     *   (treated as a pre-0.24 CLI to keep the historical behaviour).
     * @param rebasePath transforms file-path flags (used for the Melos cwd).
     */
    fun build(
        options: JasprRunConfigurationOptions,
        cliVersion: String?,
        rebasePath: (String) -> String = { it },
    ): List<String> {
        val autoAction = supportsAutoAction(cliVersion)
        val args = mutableListOf<String>()

        if (options.verbose) args += "--verbose"

        options.input.takeIf { it.isNotBlank() }
            ?.let { args += listOf("--input", rebasePath(it)) }

        if (autoAction) {
            options.mode.takeIf { it in autoActions }
                ?.let { args += listOf("--auto", it) }
        } else {
            options.mode.takeIf { it in legacyModes }
                ?.let { args += listOf("--mode", it) }
        }

        options.port.takeIf { it.isNotBlank() }
            ?.let { args += listOf("--port", it) }

        if (!autoAction) {
            options.webPort.takeIf { it.isNotBlank() }
                ?.let { args += listOf("--web-port", it) }

            options.proxyPort.takeIf { it.isNotBlank() }
                ?.let { args += listOf("--proxy-port", it) }
        }

        when (options.buildMode) {
            "debug"   -> args += "--debug"
            "release" -> args += "--release"
        }

        if (options.experimentalWasm) args += "--experimental-wasm"
        if (!options.managedBuildOptions) args += "--no-managed-build-options"
        if (options.skipServer) args += "--skip-server"

        addMultiValue(args, options.dartDefine, "--dart-define")
        addMultiValue(args, options.dartDefineClient, "--dart-define-client")
        addMultiValue(args, options.dartDefineServer, "--dart-define-server")
        addMultiValue(args, options.dartDefineFromFile, "--dart-define-from-file", rebasePath)

        return args
    }

    private fun addMultiValue(
        args: MutableList<String>,
        raw: String,
        flag: String,
        transform: (String) -> String = { it },
    ) {
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            .forEach { args += listOf(flag, transform(it)) }
    }
}
