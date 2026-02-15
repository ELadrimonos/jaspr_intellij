package com.github.eladrimonos.jasprintellij.template.project

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.EnvironmentUtil
import com.jetbrains.lang.dart.sdk.DartSdkLibUtil
import com.jetbrains.lang.dart.sdk.DartSdkUtil
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.JComboBox

class JasprNewProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private val logger = Logger.getInstance(JasprNewProjectWizardStep::class.java)

    private var sdkPath: String = ""
    private var template: String? = null
    private var mode: String = "static"
    private var routing: String = "multi-page"
    private var flutter: String = "none"
    private var backend: String = "none"
    private var runPubGet: Boolean = true

    private var backendComboRef: JComboBox<String>? = null

    init {
        // Attempt to reuse the last configured Dart SDK or auto-detect it from PATH.
        val lastUsedPath = PropertiesComponent.getInstance().getValue("dart.sdk.path")
        if (!lastUsedPath.isNullOrBlank() && DartSdkUtil.isDartSdkHome(lastUsedPath)) {
            sdkPath = lastUsedPath
        } else {
            findDartInPath()?.let { sdkPath = it }
        }
    }

    override fun setupUI(builder: Panel) {
        builder.group("Dart Configuration") {
            row("Dart SDK path:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Dart SDK Path")
                )
                    .bindText(::sdkPath)
                    .align(AlignX.FILL)
                    .validationOnInput { field ->
                        if (field.text.isBlank()) return@validationOnInput error("Path required")
                        if (!DartSdkUtil.isDartSdkHome(field.text)) {
                            return@validationOnInput error("Invalid Dart SDK path. Must contain bin/dart")
                        }
                        null
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
                        { selected -> mode = MODE_OPTIONS.find { it.first == selected }?.second ?: "static" }
                    )
                    .component

                modeCombo.addItemListener {
                    val selectedName = modeCombo.selectedItem as? String
                    val selectedMode = MODE_OPTIONS.find { it.first == selectedName }?.second
                    backendComboRef?.isEnabled = (selectedMode == "server")
                }
            }

            row("Routing:") {
                comboBox(ROUTING_OPTIONS.map { it.first })
                    .bindItem(
                        { ROUTING_OPTIONS.find { it.second == routing }?.first },
                        { selected -> routing = ROUTING_OPTIONS.find { it.first == selected }?.second ?: "multi-page" }
                    )
            }

            row("Flutter support:") {
                comboBox(FLUTTER_OPTIONS.map { it.first })
                    .bindItem(
                        { FLUTTER_OPTIONS.find { it.second == flutter }?.first },
                        { selected -> flutter = FLUTTER_OPTIONS.find { it.first == selected }?.second ?: "none" }
                    )
            }

            row("Backend:") {
                backendComboRef = comboBox(BACKEND_OPTIONS.map { it.first })
                    .bindItem(
                        { BACKEND_OPTIONS.find { it.second == backend }?.first },
                        { selected -> backend = BACKEND_OPTIONS.find { it.first == selected }?.second ?: "none" }
                    )
                    .component.apply { isEnabled = false }
            }

            row {
                checkBox("Run 'dart pub get' after creating the project")
                    .bindSelected(::runPubGet)
            }
        }
    }

    companion object {
        private val MODE_OPTIONS = listOf("Static" to "static", "Server" to "server", "Client" to "client")
        private val ROUTING_OPTIONS = listOf(
            "Multi-page (Recommended)" to "multi-page",
            "Single-page" to "single-page",
            "None" to "none"
        )
        private val FLUTTER_OPTIONS = listOf("None" to "none", "Embedded" to "embedded", "Plugins only" to "plugins-only")
        private val BACKEND_OPTIONS = listOf("None" to "none", "Shelf" to "shelf")
    }

    override fun setupProject(project: Project) {
        if (!DartSdkUtil.isDartSdkHome(sdkPath)) {
            throw ConfigurationException("Invalid Dart SDK path selected.")
        }

        PropertiesComponent.getInstance().setValue("dart.sdk.path", sdkPath)

        ApplicationManager.getApplication().runWriteAction {
            DartSdkLibUtil.ensureDartSdkConfigured(project, sdkPath)
        }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                executeJasprCreate(project)
            },
            "Creating Jaspr Project...",
            false,
            project
        )
    }

    /**
     * Executes the Jaspr CLI in a temporary directory and then moves
     * the generated project into the final project location.
     */
    private fun executeJasprCreate(project: Project) {
        val projectPath = project.basePath ?: throw ConfigurationException("Project path not found")
        val projectDir = File(projectPath)
        val parentDir = projectDir.parentFile
        val projectName = projectDir.name

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExecutable = File(sdkPath, "bin/$dartExeName").absolutePath

        val tempDir = File(parentDir, "${projectName}_temp_${System.currentTimeMillis()}")
        val tempProjectName = tempDir.name

        try {
            val commandLine = GeneralCommandLine()
                .withExePath(dartExecutable)
                .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "create")
                .withWorkDirectory(parentDir)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(EnvironmentUtil.getEnvironmentMap())

            template?.let { commandLine.addParameters("-t", it) }
            commandLine.addParameters("-m", mode)
            commandLine.addParameters("-r", routing)
            commandLine.addParameters("-f", flutter)
            if (mode == "server" && backend != "none") commandLine.addParameters("-b", backend)
            if (!runPubGet) commandLine.addParameter("--no-pub-get")

            commandLine.addParameter(tempProjectName)

            val output: ProcessOutput = ExecUtil.execAndGetOutput(commandLine)

            if (output.exitCode != 0) {
                throw ConfigurationException("Error running Jaspr CLI: ${output.stderr}\n${output.stdout}")
            }

            tempDir.listFiles()?.forEach { file ->
                val targetFile = File(projectDir, file.name)
                if (file.isDirectory) {
                    file.copyRecursively(targetFile, overwrite = true)
                } else {
                    file.copyTo(targetFile, overwrite = true)
                }
            }

            replaceProjectNameInFiles(projectDir, tempProjectName, projectName)

            tempDir.deleteRecursively()

            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(projectDir)
                    ?.refresh(false, true)
            }

        } catch (e: Exception) {
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            throw ConfigurationException("Unexpected error: ${e.message}")
        }
    }

    private fun replaceProjectNameInFiles(projectDir: File, oldName: String, newName: String) {
        val filesToUpdate = listOf(
            "pubspec.yaml",
            "README.md",
            "analysis_options.yaml"
        )

        filesToUpdate.forEach { fileName ->
            val file = File(projectDir, fileName)
            if (file.exists()) {
                try {
                    val content = file.readText(StandardCharsets.UTF_8)
                    val updatedContent = content.replace(oldName, newName)
                    file.writeText(updatedContent, StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    logger.warn("Could not update project name in $fileName: ${e.message}")
                }
            }
        }
    }

    /**
     * Attempts to locate a Dart SDK by scanning the system PATH
     * and validating potential SDK home directories.
     */
    private fun findDartInPath(): String? {
        val envPath = EnvironmentUtil.getValue("PATH") ?: return null
        val exeName = if (SystemInfo.isWindows) "dart.exe" else "dart"

        for (pathDir in envPath.split(File.pathSeparator)) {
            val file = File(pathDir, exeName)
            if (file.exists() && file.canExecute()) {
                val potentialSdkHome = file.parentFile?.parent
                if (potentialSdkHome != null && DartSdkUtil.isDartSdkHome(potentialSdkHome)) {
                    return potentialSdkHome
                }
            }
        }
        return null
    }
}