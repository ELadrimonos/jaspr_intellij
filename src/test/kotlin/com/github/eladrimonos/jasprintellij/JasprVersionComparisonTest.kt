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
        runTestsForVersion("0.23.0+3")
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
        
        // Fix for 0.22.4 dependency conflict with jaspr_router
        if (version == "0.22.4") {
            val pubspecFile = File(projectDir, "pubspec.yaml")
            val content = pubspecFile.readText()
            if (content.contains("jaspr_router: ^0.8.2")) {
                println("Fixing jaspr_router version conflict for 0.22.4...")
                pubspecFile.writeText(content.replace("jaspr_router: ^0.8.2", "jaspr_router: 0.8.1"))
            }
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

        // 2. Test Daemon Execution
        val latch = CountDownLatch(1)
        var serverUri: String? = null
        
        val options = JasprRunConfigurationOptions().apply {
            verbose = true
        }

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

        // 3. Test Tooling Service Behavior
        service.start()
        val isAtLeast023 = version.startsWith("0.23")
        assertEquals("Service useFileSystemScopes mismatch for version $version", isAtLeast023, service.useFileSystemScopes)
        
        // 4. Test HTML Conversion (Directly via service)
        // Wait a bit for daemon to connect if < 0.23
        if (!isAtLeast023) {
            Thread.sleep(2000) 
        }
        
        val html = "<div>Hello World</div>"
        val converted = service.convertHtml(html)
        assertNotNull("HTML conversion returned null for version $version", converted)
        assertTrue("Converted HTML should contain 'div' for version $version", converted!!.contains("div"))
        
        println("✓ Tooling service behavior and HTML conversion verified for version $version")
    }
}
