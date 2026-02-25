package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.ui.RunConfigurationFragmentedEditor
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExpandableTextField
import java.awt.BorderLayout
import java.util.function.Predicate

class JasprSettingsEditor(
    runConfiguration: JasprRunConfiguration,
    private val project: Project
) : RunConfigurationFragmentedEditor<JasprRunConfiguration>(runConfiguration, null) {

    override fun createRunFragments(): List<SettingsEditorFragment<JasprRunConfiguration, *>> {
        val fragments = mutableListOf<SettingsEditorFragment<JasprRunConfiguration, *>>()

        // -----------------------------------------------------------------------
        // 1. Entry File (always visible)
        // -----------------------------------------------------------------------
        val inputField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptorFactory
                .createSingleFileDescriptor("dart")
                .withTitle("Select Entry File")
                .withDescription("Select the *.server.dart entry file for the server app")
                .withFileFilter { it.name.endsWith(".server.dart") }
            addBrowseFolderListener(project, descriptor)
        }
        val labeledInput = LabeledComponent.create(inputField, "Entry point:", BorderLayout.WEST)
        val projectBasePath = project.basePath ?: ""
        fragments.add(SettingsEditorFragment(
            "jaspr.input", "Entry file", null, labeledInput,
            // reset: stored relative path → display as absolute in the UI
            { config: JasprRunConfiguration, c: LabeledComponent<TextFieldWithBrowseButton> ->
                val stored = config.input
                c.component.text = if (stored.isNotBlank() && !stored.startsWith("/") && projectBasePath.isNotBlank())
                    "$projectBasePath/$stored"
                else
                    stored
            },
            // apply: absolute path from UI → store as relative to project root
            { config: JasprRunConfiguration, c: LabeledComponent<TextFieldWithBrowseButton> ->
                val abs = c.component.text
                config.input = if (projectBasePath.isNotBlank() && abs.startsWith(projectBasePath))
                    abs.removePrefix("$projectBasePath/")
                else
                    abs
            },
            Predicate { true }
        ).apply { isRemovable = false })

        // -----------------------------------------------------------------------
        // 2. Reload Mode (always visible)
        // -----------------------------------------------------------------------
        val modeCombo = ComboBox(arrayOf("refresh", "reload"))
        val labeledMode = LabeledComponent.create(modeCombo, "Reload mode:", BorderLayout.WEST)
        fragments.add(SettingsEditorFragment(
            "jaspr.mode", "Reload mode", null, labeledMode,
            { config: JasprRunConfiguration, c: LabeledComponent<ComboBox<String>> -> c.component.selectedItem = config.mode.ifBlank { "refresh" } },
            { config: JasprRunConfiguration, c: LabeledComponent<ComboBox<String>> -> config.mode = c.component.selectedItem as? String ?: "refresh" },
            Predicate { true }
        ).apply { isRemovable = false })

        // -----------------------------------------------------------------------
        // 3. Helper functions for building labeled text fragments
        // -----------------------------------------------------------------------
        fun createTextFragment(
            id: String, name: String, group: String, labelText: String, defaultVal: String,
            getter: (JasprRunConfiguration) -> String,
            setter: (JasprRunConfiguration, String) -> Unit,
            isDefaultVisible: Boolean = false
        ): SettingsEditorFragment<JasprRunConfiguration, LabeledComponent<JBTextField>> {
            val field = JBTextField().apply { emptyText.text = defaultVal }
            val labeled = LabeledComponent.create(field, labelText, BorderLayout.WEST)

            return SettingsEditorFragment<JasprRunConfiguration, LabeledComponent<JBTextField>>(
                id, name, group, labeled,
                { config: JasprRunConfiguration, c: LabeledComponent<JBTextField> -> c.component.text = getter(config) },
                { config: JasprRunConfiguration, c: LabeledComponent<JBTextField> -> setter(config, c.component.text) },
                Predicate { isDefaultVisible || getter(it).isNotBlank() } // visible if it has a value or is shown by default
            )
        }

        fun createDartDefineFragment(
            id: String, name: String, labelText: String,
            getter: (JasprRunConfiguration) -> String,
            setter: (JasprRunConfiguration, String) -> Unit
        ): SettingsEditorFragment<JasprRunConfiguration, LabeledComponent<ExpandableTextField>> {
            val field = ExpandableTextField({ it.split("\n") }, { it.joinToString("\n") })
            val labeled = LabeledComponent.create(field, labelText, BorderLayout.WEST)

            return SettingsEditorFragment<JasprRunConfiguration, LabeledComponent<ExpandableTextField>>(
                id, name, "Dart Defines", labeled,
                { config: JasprRunConfiguration, c: LabeledComponent<ExpandableTextField> -> c.component.text = getter(config) },
                { config: JasprRunConfiguration, c: LabeledComponent<ExpandableTextField> -> setter(config, c.component.text) },
                Predicate { getter(it).isNotBlank() } // hidden by default in "Modify Options"
            )
        }

        // -----------------------------------------------------------------------
        // 4. Fields
        // -----------------------------------------------------------------------

        // Ports — only the main port is visible by default
        fragments.add(createTextFragment("jaspr.port", "Port", "Server", "Port:", "8080", { it.port }, { c, v -> c.port = v }, true))
        fragments.add(createTextFragment("jaspr.webPort", "Web port", "Server", "Web port:", "5467", { it.webPort }, { c, v -> c.webPort = v }))
        fragments.add(createTextFragment("jaspr.proxyPort", "Proxy port", "Server", "Proxy port:", "5567", { it.proxyPort }, { c, v -> c.proxyPort = v }))

        // Build Mode
        val buildModeCombo = ComboBox(arrayOf("dev (default)", "debug", "release"))
        val labeledBuildMode = LabeledComponent.create(buildModeCombo, "Build mode:", BorderLayout.WEST)
        fragments.add(SettingsEditorFragment<JasprRunConfiguration, LabeledComponent<ComboBox<String>>>(
            "jaspr.buildMode", "Build mode", "Build", labeledBuildMode,
            { config: JasprRunConfiguration, c: LabeledComponent<ComboBox<String>> -> c.component.selectedItem = config.buildMode.ifBlank { "dev (default)" } },
            { config: JasprRunConfiguration, c: LabeledComponent<ComboBox<String>> ->
                val sel = c.component.selectedItem as? String ?: ""
                config.buildMode = if (sel.startsWith("dev")) "" else sel
            },
            Predicate { it.buildMode.isNotBlank() }
        ))

        // -----------------------------------------------------------------------
        // 5. Boolean flags as tag chips
        // -----------------------------------------------------------------------
        fragments.add(SettingsEditorFragment.createTag(
            "jaspr.verbose", "Verbose logging", "Logs",
            Predicate { it.verbose }
        ) { config: JasprRunConfiguration, value: Boolean -> config.verbose = value })
        fragments.add(SettingsEditorFragment.createTag(
            "jaspr.wasm", "Compile to WASM", "Build",
            Predicate { it.experimentalWasm }
        ) { config: JasprRunConfiguration, value: Boolean -> config.experimentalWasm = value })
        fragments.add(SettingsEditorFragment.createTag(
            "jaspr.skipServer", "Skip server", "Server",
            Predicate { it.skipServer }
        ) { config: JasprRunConfiguration, value: Boolean -> config.skipServer = value })
        // FIXME Currently throws error for port usage and server process keeps running until manually stopped
        fragments.add(SettingsEditorFragment.createTag(
            "jaspr.chrome", "Launch in Chrome", "Browser",
            Predicate { it.launchInChrome }
        ) { config: JasprRunConfiguration, value: Boolean -> config.launchInChrome = value })
        fragments.add(SettingsEditorFragment.createTag(
            "jaspr.managed", "Managed build options", "Build",
            Predicate { it.managedBuildOptions }
        ) { config: JasprRunConfiguration, value: Boolean -> config.managedBuildOptions = value })

        // -----------------------------------------------------------------------
        // 6. Dart Defines — hidden in "Modify Options" until explicitly added
        // -----------------------------------------------------------------------
        fragments.add(createDartDefineFragment("jaspr.dartDefine", "Dart define", "--dart-define:", { it.dartDefine }, { c, v -> c.dartDefine = v }))
        fragments.add(createDartDefineFragment("jaspr.dartDefineClient", "Dart define client", "--dart-define-client:", { it.dartDefineClient }, { c, v -> c.dartDefineClient = v }))
        fragments.add(createDartDefineFragment("jaspr.dartDefineServer", "Dart define server", "--dart-define-server:", { it.dartDefineServer }, { c, v -> c.dartDefineServer = v }))
        fragments.add(createDartDefineFragment("jaspr.dartDefineFromFile", "Dart define from file", "--dart-define-from-file:", { it.dartDefineFromFile }, { c, v -> c.dartDefineFromFile = v }))

        return fragments
    }
}