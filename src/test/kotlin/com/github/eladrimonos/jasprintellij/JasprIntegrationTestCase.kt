package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.execution.JasprDaemonProcessHandler
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationOptions
import com.github.eladrimonos.jasprintellij.services.DefaultCliRunner
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.charset.StandardCharsets

abstract class JasprIntegrationTestCase : BasePlatformTestCase() {

    protected val sdkPath: String by lazy {
        System.getenv("DART_HOME")
            ?: findDartSdkFromPath()
            ?: error(
                "DART_HOME environment variable is missing and Dart SDK was not found in PATH. " +
                        "Set DART_HOME to your Dart SDK home (the folder that contains bin/dart)."
            )
    }

    override fun setUp() {
        super.setUp()
        // Ensure project base path exists as a real directory
        project.basePath?.let { File(it).mkdirs() }

        // Configure Dart SDK in the project so JasprDartSdkResolver finds it.
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            com.jetbrains.lang.dart.sdk.DartSdkLibUtil.ensureDartSdkConfigured(project, sdkPath)
        }
    }

    private fun findDartSdkFromPath(): String? {
        val path = System.getenv("PATH") ?: return null
        val dartExeName = if (com.intellij.openapi.util.SystemInfo.isWindows) "dart.exe" else "dart"

        for (dir in path.split(File.pathSeparator)) {
            val dart = File(dir, dartExeName)
            if (dart.exists() && dart.canExecute()) {
                val sdkHome = dart.parentFile?.parentFile ?: continue
                val dartInSdk = File(sdkHome, "bin/$dartExeName")
                if (dartInSdk.exists()) return sdkHome.absolutePath
            }
        }
        return null
    }

    protected fun installJasprCli(version: String) {
        println("Installing jaspr_cli version $version...")
        val dartExe = File(sdkPath, "bin/dart").absolutePath
        val cmd = GeneralCommandLine(dartExe, "pub", "global", "activate", "jaspr_cli", version)
            .withCharset(StandardCharsets.UTF_8)
        val result = DefaultCliRunner.run(cmd)
        if (result.exitCode != 0) {
            error("Failed to install jaspr_cli $version: ${result.stderr}\n${result.stdout}")
        }
        println("Successfully installed jaspr_cli version $version")
    }

    protected fun createTestProjectDir(name: String): File {
        val root = File(System.getProperty("java.io.tmpdir"), "jaspr_intellij_integration_tests")
        return File(root, name).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
    }

    protected fun createProject(projectDir: File, options: JasprProjectCreator.Options = JasprProjectCreator.Options(runPubGet = false)) {
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)
        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = options
        )
    }

    protected fun runDaemon(projectDir: File, options: JasprRunConfigurationOptions, onServerStarted: (String) -> Unit): JasprDaemonProcessHandler {
        val dartExeName = if (com.intellij.openapi.util.SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withWorkDirectory(projectDir.absolutePath)
            .withCharset(StandardCharsets.UTF_8)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "daemon")

        if (options.verbose) cmd.addParameter("--verbose")

        val handler = JasprDaemonProcessHandler(
            cmd,
            onServerStarted = onServerStarted
        )
        handler.startNotify()
        return handler
    }

    protected fun assertBasicProjectStructure(projectDir: File, expectedProjectName: String) {
        assertTrue("Project directory should exist: ${projectDir.absolutePath}", projectDir.exists())
        assertTrue("pubspec.yaml should exist", File(projectDir, "pubspec.yaml").exists())
        val pubspecContent = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain project name", pubspecContent.contains("name: $expectedProjectName"))
    }
}
