package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.execution.JasprDaemonProcessHandler
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprDaemonFlags
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationOptions
import com.github.eladrimonos.jasprintellij.services.DefaultCliRunner
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Base class for tests that drive the real `jaspr_cli`.
 *
 * The Gradle `test` task points `PUB_CACHE` at `build/test-pub-cache` (see
 * build.gradle.kts), so every `dart pub global activate` here goes to that isolated
 * cache and never changes the developer's own global jaspr_cli / melos.
 */
abstract class JasprIntegrationTestCase : BasePlatformTestCase() {

    companion object {
        /** jaspr_cli version installed for tests that don't pick one themselves. */
        const val DEFAULT_CLI_VERSION = "0.23.4"
    }

    /**
     * The jaspr_cli version [setUp] makes sure is active, or null for tests that
     * install versions themselves (e.g. [JasprVersionComparisonTest]).
     */
    protected open val requiredCliVersion: String? = DEFAULT_CLI_VERSION

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

        // sdkPath (e.g. CI's DART_HOME) may live outside the test sandbox's allowed
        // VFS roots (esp. on Windows), causing VfsRootAccessNotAllowedError.
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, sdkPath)

        // Configure Dart SDK in the project so JasprDartSdkResolver finds it.
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            com.jetbrains.lang.dart.sdk.DartSdkLibUtil.ensureDartSdkConfigured(project, sdkPath)
        }

        requiredCliVersion?.let { if (activeCliVersion() != it) installJasprCli(it) }
    }

    /**
     * jaspr_cli starts the server with a bare `--enable-vm-service`, i.e. always on
     * port 8181. If something else holds that port the daemon never emits
     * `server.started`; fail fast with the reason instead of timing out.
     */
    protected fun assertVmServicePortFree() {
        val inUse = runCatching { java.net.ServerSocket(8181, 1, java.net.InetAddress.getLoopbackAddress()).close() }.isFailure
        assertFalse(
            "Port 8181 (Dart VM service, hard-coded by jaspr_cli) is already in use by another process. " +
                "Stop it before running daemon integration tests.",
            inUse,
        )
    }

    /** Polls instead of ProcessHandler.waitFor, which shouldn't block the EDT platform tests run on. */
    protected fun awaitTermination(handler: com.intellij.execution.process.ProcessHandler, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) Thread.sleep(100)
        return handler.isProcessTerminated
    }

    /**
     * Asserts that stopping the daemon left nothing behind on port 8181 — a leaked
     * jaspr server/DDS would block every later run (see JasprDaemonProcessHandler).
     */
    protected fun assertVmServicePortReleased(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { java.net.ServerSocket(8181, 1, java.net.InetAddress.getLoopbackAddress()).close() }.isSuccess) return
            Thread.sleep(200)
        }
        fail("Port 8181 is still in use ${timeoutMs}ms after stopping the daemon: a jaspr server/DDS process leaked.")
    }

    /** The jaspr_cli version currently active in this pub cache, or null if none. */
    protected fun activeCliVersion(): String? {
        val cmd = GeneralCommandLine(dartExe(), "pub", "global", "run", "jaspr_cli:jaspr", "--version")
            .withCharset(StandardCharsets.UTF_8)
        val result = DefaultCliRunner.run(cmd)
        if (result.exitCode != 0) return null
        return Regex("""\d+\.\d+\.\d+(-[\w.]+)?""").find(result.stdout)?.value
    }

    private fun dartExe(): String =
        File(sdkPath, "bin/${if (com.intellij.openapi.util.SystemInfo.isWindows) "dart.exe" else "dart"}").absolutePath

    /**
     * Rewrites dependency constraints in a CLI-generated pubspec.yaml that no longer
     * resolve with today's pub.dev, so tests don't break whenever a transitive
     * dependency publishes a release incompatible with an older jaspr_builder.
     */
    protected fun patchPubspecForCompatibility(pubspec: File, cliVersion: String) {
        val original = pubspec.readText(StandardCharsets.UTF_8)
        var patched = original
        val tooling = com.github.eladrimonos.jasprintellij.services.JasprTooling()

        // jaspr 0.22.x: CLI templates may emit jaspr_router ^0.8.2 or ^0.9.x, which need jaspr ^0.23.
        if (!tooling.isVersionAtLeast(cliVersion, "0.23.0")) {
            patched = patched.replace(Regex("""jaspr_router: \^0\.(8\.2|9\.\d+)"""), "jaspr_router: 0.8.1")
        }

        // The 0.24.0-beta.0 template still emits jaspr_router ^0.9.0 (needs jaspr ^0.23);
        // the matching router release is 0.10.0-beta.0.
        if (cliVersion.startsWith("0.24.0-beta")) {
            patched = patched.replace(Regex("""jaspr_router: \^0\.9\.\d+"""), "jaspr_router: ^0.10.0-beta.0")
        }

        // build_web_compilers >= 4.8.6 needs analyzer >= 13.3, which jaspr_builder < 0.24
        // (analyzer ^10 / ^12) can't use. Pin back to 4.8.5 for those versions.
        if (!tooling.isVersionAtLeast(cliVersion, "0.24.0")) {
            patched = patched.replace(Regex("""build_web_compilers: \^4\.8\.\d+"""), "build_web_compilers: ^4.8.5")
        }

        if (patched != original) {
            println("Patched ${pubspec.path} for jaspr_cli $cliVersion")
            pubspec.writeText(patched, StandardCharsets.UTF_8)
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

    protected fun installMelos() {
        println("Installing melos...")
        val dartExe = File(sdkPath, "bin/dart").absolutePath
        val cmd = GeneralCommandLine(dartExe, "pub", "global", "activate", "melos")
            .withCharset(StandardCharsets.UTF_8)
        val result = DefaultCliRunner.run(cmd)
        if (result.exitCode != 0) {
            error("Failed to install melos: ${result.stderr}\n${result.stdout}")
        }
        println("Successfully installed melos")
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

    protected fun runDaemon(
        projectDir: File,
        options: JasprRunConfigurationOptions,
        cliVersion: String? = null,
        onServerStarted: (String) -> Unit,
    ): JasprDaemonProcessHandler {
        val dartExeName = if (com.intellij.openapi.util.SystemInfo.isWindows) "dart.exe" else "dart"
        val dartExe = File(sdkPath, "bin/$dartExeName").absolutePath

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withWorkDirectory(projectDir.absolutePath)
            .withCharset(StandardCharsets.UTF_8)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "daemon")

        // Same version-aware flags as JasprRunProfileState (--mode vs --auto, port options).
        cmd.addParameters(JasprDaemonFlags.build(options, cliVersion))

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
