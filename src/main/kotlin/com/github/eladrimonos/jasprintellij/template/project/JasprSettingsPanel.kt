package com.github.eladrimonos.jasprintellij.template.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.lang.dart.sdk.DartSdkUtil
import java.io.File
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

class JasprSettingsPanel {

    // ── State ────────────────────────────────────────────────────────────────

    private var sdkPathInitial: String = ""

    var template: String? = null
    var mode: String = "static"
    var routing: String = "multi-page"
    var flutter: String = "none"
    var backend: String = "none"
    var runPubGet: Boolean = true

    // Direct reference to the text field so validate() always reads the
    // live value regardless of focus state, and so the generator peer can
    // attach a DocumentListener to trigger revalidation on every keystroke.
    private var sdkField: JTextField? = null

    /** Exposed for [JasprDirectoryProjectGenerator] to attach a DocumentListener. */
    val sdkTextField: JTextField? get() = sdkField
    private var backendCombo: JComboBox<String>? = null

    /**
     * Always returns the live text from the field (not the bindText-delayed
     * backing property). Safe to call from any thread after the UI is built.
     */
    val sdkPath: String
        get() = sdkField?.text?.trim() ?: sdkPathInitial

    init {
        val last = PropertiesComponent.getInstance().getValue("dart.sdk.path")
        if (!last.isNullOrBlank() && DartSdkUtil.isDartSdkHome(last)) {
            sdkPathInitial = last
        } else {
            findDartInPath()?.let { sdkPathInitial = it }
        }
    }

    // ── Component ────────────────────────────────────────────────────────────

    val component: JComponent by lazy { buildPanel() }

    fun buildInto(builder: Panel) = buildRows(builder)

    // ── Builders ─────────────────────────────────────────────────────────────

    private fun buildPanel(): JComponent = panel { buildRows(this) }

    private fun buildRows(builder: Panel) {
        builder.group("Dart Configuration") {
            row("Dart SDK path:") {
                val cell = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Dart SDK Path")
                )
                    .align(AlignX.FILL)

                // Keep a direct reference to the underlying JTextField so
                // sdkPath getter always reflects what the user typed, even
                // before the field loses focus.
                sdkField = (cell.component.childComponent as? JTextField)
                    ?: cell.component.textField

                // Seed the field with the pre-detected path.
                sdkField?.text = sdkPathInitial

                // validationOnInput works for IDEA's wizard framework.
                // For WebStorm the peer's validate() is what matters — both
                // read sdkPath which delegates to sdkField.text directly.
                cell.validationOnInput { field ->
                    when {
                        field.text.isBlank() ->
                            error("Dart SDK path is required")
                        !DartSdkUtil.isDartSdkHome(field.text) ->
                            error(
                                "Invalid Dart SDK path. Must contain bin/dart. " +
                                        "If you use Flutter, try: flutter/bin/cache/dart-sdk"
                            )
                        else -> null
                    }
                }
            }
        }

        builder.group("Jaspr Configuration") {
            row("Template:") {
                comboBox(listOf("Default", "Documentation Site"))
                    .bindItem(
                        { if (template == "docs") "Documentation Site" else "Default" },
                        { template = if (it == "Documentation Site") "docs" else null }
                    )
            }

            row("Rendering mode:") {
                val modeCombo = comboBox(MODE_OPTIONS.map { it.first })
                    .bindItem(
                        { MODE_OPTIONS.find { it.second == mode }?.first },
                        { selected ->
                            mode = MODE_OPTIONS.find { it.first == selected }?.second ?: "static"
                        }
                    )
                    .component

                modeCombo.addItemListener {
                    val selectedMode = MODE_OPTIONS
                        .find { it.first == modeCombo.selectedItem }?.second
                    backendCombo?.isEnabled = (selectedMode == "server")
                }
            }

            row("Routing:") {
                comboBox(ROUTING_OPTIONS.map { it.first })
                    .bindItem(
                        { ROUTING_OPTIONS.find { it.second == routing }?.first },
                        { selected ->
                            routing = ROUTING_OPTIONS.find { it.first == selected }?.second
                                ?: "multi-page"
                        }
                    )
            }

            row("Flutter support:") {
                comboBox(FLUTTER_OPTIONS.map { it.first })
                    .bindItem(
                        { FLUTTER_OPTIONS.find { it.second == flutter }?.first },
                        { selected ->
                            flutter =
                                FLUTTER_OPTIONS.find { it.first == selected }?.second ?: "none"
                        }
                    )
            }

            row("Backend:") {
                backendCombo = comboBox(BACKEND_OPTIONS.map { it.first })
                    .bindItem(
                        { BACKEND_OPTIONS.find { it.second == backend }?.first },
                        { selected ->
                            backend =
                                BACKEND_OPTIONS.find { it.first == selected }?.second ?: "none"
                        }
                    )
                    .component
                    .apply { isEnabled = false }
            }

            row {
                checkBox("Run 'dart pub get' after creating the project")
                    .bindSelected(::runPubGet)
            }
        }
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    fun toCreatorOptions() =
        com.github.eladrimonos.jasprintellij.services.JasprProjectCreator.Options(
            template = template,
            mode = mode,
            routing = routing,
            flutter = flutter,
            backend = backend,
            runPubGet = runPubGet,
        )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun findDartInPath(): String? {
        val envPath = com.intellij.util.EnvironmentUtil.getValue("PATH") ?: return null
        val exeName = if (com.intellij.openapi.util.SystemInfo.isWindows) "dart.exe" else "dart"
        for (dir in envPath.split(File.pathSeparator)) {
            val exe = File(dir, exeName)
            if (exe.exists() && exe.canExecute()) {
                val sdkHome = exe.parentFile?.parent
                if (sdkHome != null && DartSdkUtil.isDartSdkHome(sdkHome)) return sdkHome
            }
        }
        return null
    }

    companion object {
        val MODE_OPTIONS =
            listOf("Static" to "static", "Server" to "server", "Client" to "client")
        val ROUTING_OPTIONS = listOf(
            "Multi-page (Recommended)" to "multi-page",
            "Single-page" to "single-page",
            "None" to "none",
        )
        val FLUTTER_OPTIONS =
            listOf("None" to "none", "Embedded" to "embedded", "Plugins only" to "plugins-only")
        val BACKEND_OPTIONS = listOf("None" to "none", "Shelf" to "shelf")
    }
}