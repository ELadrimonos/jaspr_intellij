package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets

class JasprProjectCreator(
    private val cliRunner: CliRunner = DefaultCliRunner,
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val environmentProvider: () -> Map<String, String> = { EnvironmentUtil.getEnvironmentMap() },
    private val logger: Logger = Logger.getInstance(JasprProjectCreator::class.java),
) {

    private val tooling: JasprTooling = JasprTooling(
        cliRunner = cliRunner,
        environmentProvider = environmentProvider,
        logger = Logger.getInstance(JasprTooling::class.java),
    )

    data class Options(
        val template: String? = null,
        val mode: String = "static",
        val routing: String = "multi-page",
        val flutter: String = "none",
        val backend: String = "none",
        val runPubGet: Boolean = true,
    )

    fun preflightCheck(sdkPath: String) {
        tooling.preflightCheck(sdkPath)
    }

    fun create(projectDir: File, sdkPath: String, options: Options) {
        val parentDir = projectDir.parentFile ?: throw ConfigurationException("Project parent directory not found")
        val projectName = projectDir.name

        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExecutable = File(sdkPath, "bin/$dartExeName").absolutePath

        val tempDir = File(parentDir, "${projectName}_temp_${timeProvider()}")
        val tempProjectName = tempDir.name

        try {
            val commandLine = GeneralCommandLine()
                .withExePath(dartExecutable)
                .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "create")
                .withWorkDirectory(parentDir)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(environmentProvider())

            options.template?.let { commandLine.addParameters("-t", it) }
            commandLine.addParameters("-m", options.mode)
            commandLine.addParameters("-r", options.routing)
            commandLine.addParameters("-f", options.flutter)
            if (options.mode == "server") {
                val effectiveBackend = options.backend.takeUnless { it == "none" } ?: "shelf"
                commandLine.addParameters("-b", effectiveBackend)
            }
            if (!options.runPubGet) commandLine.addParameter("--no-pub-get")

            commandLine.addParameter(tempProjectName)

            val output: ProcessOutput = cliRunner.run(commandLine)
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
        } catch (e: Exception) {
            if (tempDir.exists()) tempDir.deleteRecursively()
            throw ConfigurationException("Unexpected error: ${e.message}")
        }
    }

    private fun replaceProjectNameInFiles(projectDir: File, oldName: String, newName: String) {
        val filesToUpdate = listOf(
            "pubspec.yaml",
            "README.md",
            "analysis_options.yaml",
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
}