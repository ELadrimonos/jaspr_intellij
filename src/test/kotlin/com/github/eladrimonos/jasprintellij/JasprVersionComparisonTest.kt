package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationOptions
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JasprVersionComparisonTest : JasprIntegrationTestCase() {

    // Each test installs the version it exercises.
    override val requiredCliVersion: String? = null

    fun testVersion0224() {
        runTestsForVersion("0.22.4")
    }

    fun testVersion0230() {
        runTestsForVersion("0.23.4")
    }

    fun testVersion0240Beta() {
        runTestsForVersion("0.24.0-beta.0")
    }

    private fun runTestsForVersion(version: String) {
        // Reset service state before changing CLI versions
        val service = project.getService(JasprToolingDaemonService::class.java)
        service.stop()

        installJasprCli(version)

        // 1. Test Project Creation
        val projectName = "test_project_${version.replace(Regex("[.+-]"), "_")}"
        val projectDir = createTestProjectDir(projectName)
        
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "static",
            runPubGet = false // We handle pub get manually to be more robust
        ))

        assertBasicProjectStructure(projectDir, projectName)

        // Patch pubspec for dependency conflicts before pub get
        val pubspecFile = File(projectDir, "pubspec.yaml")
        patchPubspecForCompatibility(pubspecFile, version)

        // Run pub get manually
        println("Running pub get for $projectName...")
        val dartExe = File(sdkPath, "bin/dart").absolutePath
        val pubGetCmd = com.intellij.execution.configurations.GeneralCommandLine(dartExe, "pub", "get")
            .withWorkDirectory(projectDir)
        val pubGetResult = com.github.eladrimonos.jasprintellij.services.DefaultCliRunner.run(pubGetCmd)
        assertTrue("pub get failed for version $version: ${pubGetResult.stderr}", pubGetResult.exitCode == 0)

        println("✓ Project creation and pub get verified for version $version")

        // Sync files to project base path so JasprToolingDaemonService finds them
        val basePath = project.basePath ?: error("Project base path is null")
        projectDir.copyRecursively(File(basePath), overwrite = true)

        // 2. Test Daemon Execution (>= 0.23 only)
        // 0.22.4 daemon depends on build_daemon port file that fails under modern
        // build_web_compilers. CLI 0.22.4 + current SDK combo is unsupported.
        val isAtLeast023 = JasprTooling().isVersionAtLeast(version, "0.23.0")
        var serverUri: String? = null

        if (isAtLeast023) {
            assertVmServicePortFree()
            val latch = CountDownLatch(1)
            val options = JasprRunConfigurationOptions().apply { verbose = true }
            val handler = runDaemon(projectDir, options, cliVersion = version) { uri ->
                serverUri = uri
                latch.countDown()
            }
            handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    println("[daemon:$version:$outputType] ${event.text}")
                }
            })
            try {
                val started = latch.await(180, TimeUnit.SECONDS)
                assertTrue("Daemon failed to start and provide VM Service URI for version $version within 180s", started)
                assertNotNull("VM Service URI should not be null for version $version", serverUri)
                println("✓ Daemon execution verified for version $version (VM Service: $serverUri)")
            } finally {
                handler.destroyProcess()
            }
            assertTrue("daemon did not exit after destroyProcess()", awaitTermination(handler))
            assertVmServicePortReleased()
        } else {
            println("⊘ Skipping daemon URI assertion for legacy version $version (known incompatible with current SDK)")
        }

        // 3. Test Tooling Service Behavior
        service.start()
        assertEquals("Service useFileSystemScopes mismatch for version $version", isAtLeast023, service.useFileSystemScopes)

        // 4. Test HTML Conversion (>= 0.23 only — legacy daemon never started)
        if (isAtLeast023) {
            val html = "<div>Hello World</div>"
            val converted = service.convertHtml(html)
            assertNotNull("HTML conversion returned null for version $version", converted)
            assertTrue("Converted HTML should contain 'div' for version $version", converted!!.contains("div"))
            println("✓ HTML conversion verified for version $version")
        } else {
            println("⊘ Skipping HTML conversion for legacy version $version")
        }

        println("✓ Tooling service behavior verified for version $version")
    }
}
