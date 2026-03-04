package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Defines the options for a Jaspr run configuration.
 *
 * This class uses `StoredProperty` delegates to automatically handle the persistence
 * of the configuration options.
 */
class JasprRunConfigurationOptions : RunConfigurationOptions() {

    /** The main entry point file for the Jaspr application. Corresponds to `--input`. */
    var input: String
        get() = _input ?: ""
        set(v) { _input = v.ifEmpty { null } }
    private var _input by string("")

    /** The reload mode for the application. Can be "reload" or "refresh". Corresponds to `--mode`. */
    var mode: String
        get() = _mode ?: "refresh"
        set(v) { _mode = v.ifEmpty { null } }
    private var _mode by string("refresh")

    /** The port to run the server on. Corresponds to `--port`. */
    var port: String
        get() = _port ?: ""
        set(v) { _port = v.ifEmpty { null } }
    private var _port by string("")

    /** The port to run the web server on. Corresponds to `--web-port`. */
    var webPort: String
        get() = _webPort ?: ""
        set(v) { _webPort = v.ifEmpty { null } }
    private var _webPort by string("")

    /** The port to run the proxy server on. Corresponds to `--proxy-port`. */
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
}