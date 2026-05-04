package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.services.DefaultCliRunner
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import java.io.File
import java.nio.charset.StandardCharsets

class JasprProjectCreatorTest : JasprIntegrationTestCase() {

    override fun setUp() {
        super.setUp()
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)
        creator.preflightCheck(sdkPath)
    }

    fun testCreateProject_StaticMode() {
        val projectDir = createTestProjectDir("jaspr_static_test")
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "static",
            routing = "multi-page",
            flutter = "none",
            backend = "none",
            runPubGet = false
        ))

        assertBasicProjectStructure(projectDir, "jaspr_static_test")

        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))

        println("✓ Static mode test passed")
    }

    fun testCreateProject_ServerMode() {
        val projectDir = createTestProjectDir("jaspr_server_test")
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "server",
            routing = "multi-page",
            flutter = "none",
            backend = "shelf",
            runPubGet = false
        ))

        assertBasicProjectStructure(projectDir, "jaspr_server_test")

        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))
        assertTrue("pubspec.yaml should contain shelf dependency", pubspec.contains("shelf"))

        val libDir = File(projectDir, "lib")
        assertTrue("lib directory should exist", libDir.exists())

        println("✓ Server mode test passed")
    }

    fun testCreateProject_ClientMode() {
        val projectDir = createTestProjectDir("jaspr_client_test")
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "client",
            routing = "single-page",
            flutter = "none",
            backend = "none",
            runPubGet = false
        ))

        assertBasicProjectStructure(projectDir, "jaspr_client_test")

        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))

        println("✓ Client mode test passed")
    }

    fun testCreateProject_DocumentationTemplate() {
        val projectDir = createTestProjectDir("jaspr_docs_test")
        createProject(projectDir, JasprProjectCreator.Options(
            template = "docs",
            mode = "static",
            routing = "multi-page",
            flutter = "none",
            backend = "none",
            runPubGet = false
        ))

        assertBasicProjectStructure(projectDir, "jaspr_docs_test")

        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain correct project name", pubspec.contains("name: jaspr_docs_test"))

        val readme = File(projectDir, "README.md").readText(StandardCharsets.UTF_8)
        assertFalse("README.md should not contain temp project name", readme.contains("_temp_"))

        println("✓ Documentation template test passed")
    }

    fun testProjectNameReplacement_AllFiles() {
        val projectDir = createTestProjectDir("my_renamed_project")
        createProject(projectDir, JasprProjectCreator.Options(
            mode = "static",
            runPubGet = false
        ))

        val expectedName = "my_renamed_project"

        val pubspecContent = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain new project name", pubspecContent.contains("name: $expectedName"))
        assertFalse("pubspec.yaml should not contain temp name", pubspecContent.contains("_temp_"))

        val readmeContent = File(projectDir, "README.md").readText(StandardCharsets.UTF_8)
        assertFalse("README.md should not contain temp name", readmeContent.contains("_temp_"))

        val analysisContent = File(projectDir, "analysis_options.yaml").readText(StandardCharsets.UTF_8)
        assertFalse("analysis_options.yaml should not contain temp name", analysisContent.contains("_temp_"))

        println("✓ Project name replacement test passed")
    }

    fun testTempDirectoryCleanup() {
        val projectDir = createTestProjectDir("cleanup_test_project")
        val parentDir = projectDir.parentFile

        createProject(projectDir, JasprProjectCreator.Options(
            mode = "static",
            runPubGet = false
        ))

        assertTrue("Project directory should exist", projectDir.exists())
        assertTrue("pubspec.yaml should exist", File(projectDir, "pubspec.yaml").exists())

        val tempDirsAfter = parentDir.listFiles()?.filter {
            it.name.contains("_temp_") && it.name.startsWith("cleanup_test_project")
        } ?: emptyList()

        assertTrue(
            "No temporary directories should remain after project creation",
            tempDirsAfter.isEmpty()
        )

        println("✓ Temp directory cleanup test passed")
    }
}