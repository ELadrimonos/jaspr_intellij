package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element

/**
 * Represents a run configuration for Jaspr applications.
 *
 * This class handles the serialization and deserialization of the configuration settings,
 * as well as providing convenience accessors to the underlying options.
 *
 * Note: Manual XML serialization/deserialization with `JDOMExternalizerUtil` is used here for
 * compatibility with older IDE versions. Modern implementations should prefer using the
 * `StoredProperty` delegates provided by `RunConfigurationOptions`. This class currently
 * acts as a bridge, delegating to a `JasprRunConfigurationOptions` instance where the
 * modern approach is used.
 */
class JasprRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<JasprRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): JasprRunConfigurationOptions =
        super.getOptions() as JasprRunConfigurationOptions

    /**
     * Saves the configuration state to an XML element.
     */
    override fun writeExternal(element: Element) {
        super.writeExternal(element)

        JDOMExternalizerUtil.writeField(element, "input", input)
        JDOMExternalizerUtil.writeField(element, "mode", mode)
        JDOMExternalizerUtil.writeField(element, "port", port)
        JDOMExternalizerUtil.writeField(element, "webPort", webPort)
        JDOMExternalizerUtil.writeField(element, "proxyPort", proxyPort)
        JDOMExternalizerUtil.writeField(element, "buildMode", buildMode)
        JDOMExternalizerUtil.writeField(element, "dartDefine", dartDefine)
        JDOMExternalizerUtil.writeField(element, "dartDefineClient", dartDefineClient)
        JDOMExternalizerUtil.writeField(element, "dartDefineServer", dartDefineServer)
        JDOMExternalizerUtil.writeField(element, "dartDefineFromFile", dartDefineFromFile)

        JDOMExternalizerUtil.writeField(element, "verbose", verbose.toString())
        JDOMExternalizerUtil.writeField(element, "experimentalWasm", experimentalWasm.toString())
        JDOMExternalizerUtil.writeField(element, "skipServer", skipServer.toString())
        JDOMExternalizerUtil.writeField(element, "launchInChrome", launchInChrome.toString())
        JDOMExternalizerUtil.writeField(element, "managedBuildOptions", managedBuildOptions.toString())
    }

    /**
     * Reads the configuration state from an XML element.
     */
    override fun readExternal(element: Element) {
        super.readExternal(element)

        input = JDOMExternalizerUtil.readField(element, "input") ?: ""
        mode = JDOMExternalizerUtil.readField(element, "mode") ?: "refresh"
        port = JDOMExternalizerUtil.readField(element, "port") ?: "8080"
        webPort = JDOMExternalizerUtil.readField(element, "webPort") ?: "5467"
        proxyPort = JDOMExternalizerUtil.readField(element, "proxyPort") ?: "5567"
        buildMode = JDOMExternalizerUtil.readField(element, "buildMode") ?: ""
        dartDefine = JDOMExternalizerUtil.readField(element, "dartDefine") ?: ""
        dartDefineClient = JDOMExternalizerUtil.readField(element, "dartDefineClient") ?: ""
        dartDefineServer = JDOMExternalizerUtil.readField(element, "dartDefineServer") ?: ""
        dartDefineFromFile = JDOMExternalizerUtil.readField(element, "dartDefineFromFile") ?: ""

        verbose = JDOMExternalizerUtil.readField(element, "verbose")?.toBoolean() ?: false
        experimentalWasm = JDOMExternalizerUtil.readField(element, "experimentalWasm")?.toBoolean() ?: false
        skipServer = JDOMExternalizerUtil.readField(element, "skipServer")?.toBoolean() ?: false
        launchInChrome = JDOMExternalizerUtil.readField(element, "launchInChrome")?.toBoolean() ?: false
        // managedBuildOptions defaults to true
        managedBuildOptions = JDOMExternalizerUtil.readField(element, "managedBuildOptions")?.toBoolean() ?: true
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        JasprSettingsEditor(this, project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        JasprRunProfileState(environment, options)

    // region Convenience accessors (delegate to options)

    var input: String
        get() = options.input
        set(v) { options.input = v }

    var mode: String
        get() = options.mode
        set(v) { options.mode = v }

    var port: String
        get() = options.port
        set(v) { options.port = v }

    var webPort: String
        get() = options.webPort
        set(v) { options.webPort = v }

    var proxyPort: String
        get() = options.proxyPort
        set(v) { options.proxyPort = v }

    var buildMode: String
        get() = options.buildMode
        set(v) { options.buildMode = v }

    var verbose: Boolean
        get() = options.verbose
        set(v) { options.verbose = v }

    var experimentalWasm: Boolean
        get() = options.experimentalWasm
        set(v) { options.experimentalWasm = v }

    var skipServer: Boolean
        get() = options.skipServer
        set(v) { options.skipServer = v }

    var launchInChrome: Boolean
        get() = options.launchInChrome
        set(v) { options.launchInChrome = v }

    var managedBuildOptions: Boolean
        get() = options.managedBuildOptions
        set(v) { options.managedBuildOptions = v }

    var dartDefine: String
        get() = options.dartDefine
        set(v) { options.dartDefine = v }

    var dartDefineClient: String
        get() = options.dartDefineClient
        set(v) { options.dartDefineClient = v }

    var dartDefineServer: String
        get() = options.dartDefineServer
        set(v) { options.dartDefineServer = v }

    var dartDefineFromFile: String
        get() = options.dartDefineFromFile
        set(v) { options.dartDefineFromFile = v }

    // endregion
}