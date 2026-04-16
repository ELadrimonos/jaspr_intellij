package com.github.eladrimonos.jasprintellij.template.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jetbrains.lang.dart.sdk.DartSdk
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.lang.dart.sdk.DartSdkUtil
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Shared settings panel for Jaspr project creation.
 *
 * Used by two different entry points:
 *  - [JasprNewProjectWizardStep] — IntelliJ IDEA's new-project wizard. The
 *    framework calls `apply()` on the DSL panel before invoking `setupProject`,
 *    which triggers all `bindItem` / `bindSelected` setters and updates the
 *    backing state properties (`template`, `mode`, etc.).
 *  - [JasprDirectoryProjectGenerator] — WebStorm's (and other small IDEs')
 *    project generator. Here `generateProject` is called **without** a prior
 *    `apply()`, so the `bindItem` setters never fire and the backing properties
 *    would remain at their default values.
 *
 * To handle both flows correctly, every combo box and the checkbox keep a
 * **direct reference to their Swing component** (alongside `bindItem`/
 * `bindSelected` for IDEA compatibility). [toCreatorOptions] then reads
 * `selectedItem` / `isSelected` from the live components, falling back to the
 * backing properties only when the UI has not been built yet.
 *
 * This mirrors the same pattern already used for [sdkPath], which reads
 * directly from [sdkField] rather than from a `bindText`-delayed property.
 */
class JasprSettingsPanel(private val project: Project? = null) {

    // ── Backing state (kept in sync by bindItem/bindSelected in IDEA) ────────

    private var sdkPathInitial: String = ""

    var template: String? = null
    var mode: String = "static"
    var routing: String = "multi-page"
    var flutter: String = "none"
    var backend: String = "none"
    var runPubGet: Boolean = true

    // ── Direct component references (used by toCreatorOptions in WebStorm) ───

    // The text field is exposed publicly so JasprDirectoryProjectGenerator can
    // attach a DocumentListener and trigger revalidation on every keystroke.
    private var sdkField: JTextField? = null
    val sdkTextField: JTextField? get() = sdkField

    // Each combo/checkbox is stored so toCreatorOptions() can read the live
    // selection even when apply() has not been called (WebStorm flow).
    private var templateCombo: JComboBox<String>? = null
    private var modeCombo: JComboBox<String>? = null
    private var routingCombo: JComboBox<String>? = null
    private var flutterCombo: JComboBox<String>? = null
    private var backendCombo: JComboBox<String>? = null
    private var runPubGetCheckBox: JCheckBox? = null

    /**
     * Always returns the live text from the SDK field, regardless of whether
     * the field has lost focus. Falls back to the pre-detected path when the
     * UI has not been built yet.
     */
    val sdkPath: String
        get() = sdkField?.text?.trim() ?: sdkPathInitial

    init {
        // SDK path resolution priority:
        // 1. Dart SDK already configured in an open project.
        // 2. Last-used path persisted in PropertiesComponent.
        // 3. dart executable found on PATH.
        sdkPathInitial = resolveInitialSdkPath() ?: ""
    }

    private fun resolveInitialSdkPath(): String? {
        // 1. From the supplied project, or any open project that has Dart configured.
        val candidates = buildList {
            if (project != null) add(project)
            addAll(ProjectManager.getInstance().openProjects)
        }
        for (p in candidates) {
            val path = DartSdk.getDartSdk(p)?.homePath
            if (!path.isNullOrBlank() && DartSdkUtil.isDartSdkHome(path)) return path
        }

        // 2. Last-saved path.
        val saved = PropertiesComponent.getInstance().getValue("dart.sdk.path")
        if (!saved.isNullOrBlank() && DartSdkUtil.isDartSdkHome(saved)) return saved

        // 3. PATH scan.
        return findDartInPath()
    }

    // ── Component ────────────────────────────────────────────────────────────

    /** Top-level component for WebStorm's [ProjectGeneratorPeer]. Built lazily. */
    val component: JComponent by lazy { buildPanel() }

    /**
     * Inlines the settings rows directly into an existing DSL [Panel].
     * Used by [JasprNewProjectWizardStep] to embed the UI inside the IDEA wizard.
     */
    fun buildInto(builder: Panel) = buildRows(builder)

    // ── Builders ─────────────────────────────────────────────────────────────

    private fun buildPanel(): JComponent = panel { buildRows(this) }

    private fun buildRows(builder: Panel) {
        builder.group("Dart Configuration") {
            row("Dart SDK path:") {
                val cell = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Dart SDK Path")
                ).align(AlignX.FILL)

                // Keep a direct reference to the underlying JTextField so
                // sdkPath always reflects what the user typed, even before
                // the field loses focus.
                sdkField = cell.component.childComponent

                // Seed the field with the pre-detected path.
                sdkField?.text = sdkPathInitial

                // validationOnInput is used by IDEA's wizard framework.
                // For WebStorm, the peer's validate() is what matters — both
                // read sdkPath, which delegates to sdkField.text directly.
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
                // bindItem keeps the backing property in sync in the IDEA flow.
                // templateCombo holds the live reference for the WebStorm flow.
                templateCombo = comboBox(listOf("Default", "Documentation Site"))
                    .bindItem(
                        { if (template == "docs") "Documentation Site" else "Default" },
                        { template = if (it == "Documentation Site") "docs" else null }
                    )
                    .component
            }

            row("Rendering mode:") {
                modeCombo = comboBox(MODE_OPTIONS.map { it.first })
                    .bindItem(
                        { MODE_OPTIONS.find { it.second == mode }?.first },
                        { selected ->
                            mode = MODE_OPTIONS.find { it.first == selected }?.second ?: "static"
                        }
                    )
                    .component

                // Enable the backend combo only when Server mode is selected.
                modeCombo!!.addItemListener {
                    val selectedMode = MODE_OPTIONS
                        .find { it.first == modeCombo!!.selectedItem }?.second
                    backendCombo?.isEnabled = (selectedMode == "server")
                }
            }

            row("Routing:") {
                routingCombo = comboBox(ROUTING_OPTIONS.map { it.first })
                    .bindItem(
                        { ROUTING_OPTIONS.find { it.second == routing }?.first },
                        { selected ->
                            routing = ROUTING_OPTIONS.find { it.first == selected }?.second
                                ?: "multi-page"
                        }
                    )
                    .component
            }

            row("Flutter support:") {
                flutterCombo = comboBox(FLUTTER_OPTIONS.map { it.first })
                    .bindItem(
                        { FLUTTER_OPTIONS.find { it.second == flutter }?.first },
                        { selected ->
                            flutter =
                                FLUTTER_OPTIONS.find { it.first == selected }?.second ?: "none"
                        }
                    )
                    .component
            }

            row("Backend:") {
                // Disabled by default; enabled dynamically when Server mode is chosen.
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
                runPubGetCheckBox = checkBox("Run 'dart pub get' after creating the project")
                    .bindSelected(::runPubGet)
                    .component
            }
        }
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /**
     * Builds the [JasprProjectCreator.Options] from the **live component state**.
     *
     * Reading from the Swing components directly (rather than from the backing
     * properties) ensures correctness in both IDE flows:
     *
     * - **IntelliJ IDEA wizard**: `apply()` is called before `setupProject`,
     *   so the backing properties are up-to-date. Both paths return the same
     *   value; the component read is just a no-op redundancy.
     * - **WebStorm generator**: `generateProject` is called without a prior
     *   `apply()`, so the backing properties still hold their initial defaults.
     *   Reading from `selectedItem` / `isSelected` is the only way to get the
     *   user's actual selection.
     *
     * The `?: backing` fallback handles the edge case where [buildRows] has not
     * been called yet (e.g. the panel was created but never displayed).
     */
    fun toCreatorOptions(): com.github.eladrimonos.jasprintellij.services.JasprProjectCreator.Options {
        val resolvedTemplate = templateCombo?.selectedItem
            ?.let { if (it == "Documentation Site") "docs" else null }
            ?: template

        val resolvedMode = modeCombo?.selectedItem
            ?.let { selected -> MODE_OPTIONS.find { it.first == selected }?.second }
            ?: mode

        val resolvedRouting = routingCombo?.selectedItem
            ?.let { selected -> ROUTING_OPTIONS.find { it.first == selected }?.second }
            ?: routing

        val resolvedFlutter = flutterCombo?.selectedItem
            ?.let { selected -> FLUTTER_OPTIONS.find { it.first == selected }?.second }
            ?: flutter

        val resolvedBackend = backendCombo?.selectedItem
            ?.let { selected -> BACKEND_OPTIONS.find { it.first == selected }?.second }
            ?: backend

        val resolvedRunPubGet = runPubGetCheckBox?.isSelected ?: runPubGet

        return com.github.eladrimonos.jasprintellij.services.JasprProjectCreator.Options(
            template = resolvedTemplate,
            mode = resolvedMode,
            routing = resolvedRouting,
            flutter = resolvedFlutter,
            backend = resolvedBackend,
            runPubGet = resolvedRunPubGet,
        )
    }

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