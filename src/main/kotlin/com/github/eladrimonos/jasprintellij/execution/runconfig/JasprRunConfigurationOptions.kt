package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Defines the options for a Jaspr run configuration.
 *
 * This class uses `StoredProperty` delegates to automatically handle the persistence
 * of the configuration options.
 */
class JasprRunConfigurationOptions : RunConfigurationOptions() {

    /**
     * Directory of the Jaspr package to serve, relative to the project root; empty means the
     * project's first Jaspr package. Used as the daemon's cwd when no [melosScript] is set.
     */
    var packageDir: String
        get() = _packageDir ?: ""
        set(v) { _packageDir = v.ifEmpty { null } }
    private var _packageDir by string("")

    /** The main entry point file for the Jaspr application. Corresponds to `--input`. */
    var input: String
        get() = _input ?: ""
        set(v) { _input = v.ifEmpty { null } }
    private var _input by string("")

    /**
     * Action performed after each rebuild: "refresh", "reload", "restart" or "none".
     * Corresponds to `--auto` on jaspr_cli >= 0.24.0, and to `--mode` (reload/refresh only) before that.
     */
    var mode: String
        get() = _mode ?: "refresh"
        set(v) { _mode = v.ifEmpty { null } }
    private var _mode by string("refresh")

    /** The port to run the server on. Corresponds to `--port`. */
    var port: String
        get() = _port ?: ""
        set(v) { _port = v.ifEmpty { null } }
    private var _port by string("")

    /** The port to run the web server on. Corresponds to `--web-port` (removed in jaspr_cli 0.24.0). */
    var webPort: String
        get() = _webPort ?: ""
        set(v) { _webPort = v.ifEmpty { null } }
    private var _webPort by string("")

    /** The port to run the proxy server on. Corresponds to `--proxy-port` (removed in jaspr_cli 0.24.0). */
    var proxyPort: String
        get() = _proxyPort ?: ""
        set(v) { _proxyPort = v.ifEmpty { null } }
    private var _proxyPort by string("")

    /** The build mode. Can be "" (dev), "debug", or "release". Corresponds to `--debug` or `--release`. */
    var buildMode: String
        get() = _buildMode ?: ""
        set(v) { _buildMode = v.ifEmpty { null } }
    private var _buildMode by string("")

    /** Enables verbose logging. Corresponds to `--verbose`. */
    private val _verbose = property(false).provideDelegate(this, ::verbose)
    var verbose: Boolean by _verbose

    /** Enables experimental WASM compilation. Corresponds to `--experimental-wasm`. */
    private val _experimentalWasm = property(false).provideDelegate(this, ::experimentalWasm)
    var experimentalWasm: Boolean by _experimentalWasm

    /**
     * Opens Dart DevTools in the browser when a Debug session attaches to the server VM.
     * Off by default: the IDE debugger is already attached, and the URL is printed to the console.
     */
    private val _openDevTools = property(false).provideDelegate(this, ::openDevTools)
    var openDevTools: Boolean by _openDevTools

    /** Skips running the server. Corresponds to `--skip-server`. */
    private val _skipServer = property(false).provideDelegate(this, ::skipServer)
    var skipServer: Boolean by _skipServer

    /** Manages build options automatically. Corresponds to `--[no-]managed-build-options`. */
    private val _managedBuildOptions = property(true).provideDelegate(this, ::managedBuildOptions)
    var managedBuildOptions: Boolean by _managedBuildOptions

    /** Newline-separated list of key=value pairs for `--dart-define`. */
    var dartDefine: String
        get() = _dartDefine ?: ""
        set(v) { _dartDefine = v.ifEmpty { null } }
    private var _dartDefine by string("")

    /** Newline-separated list of key=value pairs for `--dart-define-client`. */
    var dartDefineClient: String
        get() = _dartDefineClient ?: ""
        set(v) { _dartDefineClient = v.ifEmpty { null } }
    private var _dartDefineClient by string("")

    /** Newline-separated list of key=value pairs for `--dart-define-server`. */
    var dartDefineServer: String
        get() = _dartDefineServer ?: ""
        set(v) { _dartDefineServer = v.ifEmpty { null } }
    private var _dartDefineServer by string("")

    /** Newline-separated list of file paths for `--dart-define-from-file`. */
    var dartDefineFromFile: String
        get() = _dartDefineFromFile ?: ""
        set(v) { _dartDefineFromFile = v.ifEmpty { null } }
    private var _dartDefineFromFile by string("")

    /**
     * Optional Melos script name to run via `melos run <name>` instead of invoking
     * jaspr directly. Used when the project is part of a Dart/Melos workspace with
     * multiple Jaspr packages.
     */
    var melosScript: String
        get() = _melosScript ?: ""
        set(v) { _melosScript = v.ifEmpty { null } }
    private var _melosScript by string("")
}