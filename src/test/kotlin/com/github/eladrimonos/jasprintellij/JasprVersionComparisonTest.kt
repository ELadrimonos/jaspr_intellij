package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationOptions
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JasprVersionComparisonTest : JasprIntegrationTestCase() {

    fun testVersion0224() {
        runTestsForVersion("0.22.4")
    }

    fun testVersion0230() {
        runTestsForVersion("0.23.4")
    }

    private fun runTestsForVersion(version: String) {
        // Reset service state before changing CLI versions
        val service = project.getService(JasprToolingDaemonService::class.java)
        service.stop()

        installJasprCli(version)

        // 1. Test Project Creation
        val projectName = "test_project_${version.replace(".", "_").replace("+", "_")}"
        val projectDir = createTestProjectDir(projectName)
        
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "static",
            runPubGet = false // We handle pub get manually to be more robust
        ))

        assertBasicProjectStructure(projectDir, projectName)

        // Patch pubspec for dependency conflicts before pub get
        val pubspecFile = File(projectDir, "pubspec.yaml")
        val originalContent = pubspecFile.readText()
        var patchedContent = originalContent

        // Fix for 0.22.4 dependency conflict with jaspr_router
        // CLI templates may emit ^0.8.2 or ^0.9.x — both require jaspr ^0.23.
        if (version == "0.22.4") {
            if (patchedContent.contains("jaspr_router: ^0.8.2")) {
                println("Patching jaspr_router ^0.8.2 for 0.22.4...")
                patchedContent = patchedContent.replace("jaspr_router: ^0.8.2", "jaspr_router: 0.8.1")
            } else if (patchedContent.contains("jaspr_router: ^0.9.0")) {
                println("Patching jaspr_router ^0.9.0 for 0.22.4...")
                patchedContent = patchedContent.replace("jaspr_router: ^0.9.0", "jaspr_router: 0.8.1")
            }
        }

        // Downgrade build_web_compilers to ^4.8.5 to avoid analyzer >=13.3.0 conflict
        // with jaspr_builder's analyzer constraints (^10 for 0.22.4, ^12 for 0.23.x).
        if (patchedContent.contains("build_web_compilers: ^4.8.10")) {
            println("Patching build_web_compilers for $version...")
            patchedContent = patchedContent.replace("build_web_compilers: ^4.8.10", "build_web_compilers: ^4.8.5")
        }

        if (patchedContent != originalContent) {
            pubspecFile.writeText(patchedContent)
        }

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
        val isAtLeast023 = version.startsWith("0.23")
        var serverUri: String? = null

        if (isAtLeast023) {
            val latch = CountDownLatch(1)
            val options = JasprRunConfigurationOptions().apply { verbose = true }
            val handler = runDaemon(projectDir, options) { uri ->
                serverUri = uri
                latch.countDown()
            }
            try {
                val started = latch.await(60, TimeUnit.SECONDS)
                assertTrue("Daemon failed to start and provide VM Service URI for version $version within 60s", started)
                assertNotNull("VM Service URI should not be null for version $version", serverUri)
                println("✓ Daemon execution verified for version $version (VM Service: $serverUri)")
            } finally {
                handler.destroyProcess()
            }
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
