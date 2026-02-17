package com.github.eladrimonos.jasprintellij

import com.github.eladrimonos.jasprintellij.services.DefaultCliRunner
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.charset.StandardCharsets

class JasprProjectCreatorTest : BasePlatformTestCase() {

    private val sdkPath: String by lazy {
        System.getenv("DART_HOME")
            ?: findDartSdkFromPath()
            ?: error(
                "DART_HOME environment variable is missing and Dart SDK was not found in PATH. " +
                        "Set DART_HOME to your Dart SDK home (the folder that contains bin/dart)."
            )
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

    override fun setUp() {
        super.setUp()
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)
        creator.preflightCheck(sdkPath)
    }

    fun testCreateProject_StaticMode() {

        val projectDir = createTestProjectDir("jaspr_static_test")
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                mode = "static",
                routing = "multi-page",
                flutter = "none",
                backend = "none",
                runPubGet = false
            )
        )

        // Verificar estructura básica
        assertBasicProjectStructure(projectDir, "jaspr_static_test")

        // Verificar contenido específico de modo static
        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain project name", pubspec.contains("name: jaspr_static_test"))
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))

        println("✓ Static mode test passed")
        printProjectTree(projectDir)
    }

    fun testCreateProject_ServerMode() {
        val projectDir = createTestProjectDir("jaspr_server_test")
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                mode = "server",
                routing = "multi-page",
                flutter = "none",
                backend = "shelf",
                runPubGet = false
            )
        )

        // Verificar estructura básica
        assertBasicProjectStructure(projectDir, "jaspr_server_test")

        // Verificar contenido específico de modo server
        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))
        assertTrue("pubspec.yaml should contain shelf dependency", pubspec.contains("shelf"))

        // En modo server debería existir un archivo de servidor
        val libDir = File(projectDir, "lib")
        assertTrue("lib directory should exist", libDir.exists())

        println("✓ Server mode test passed")
        printProjectTree(projectDir)
    }

    fun testCreateProject_ClientMode() {
        val projectDir = createTestProjectDir("jaspr_client_test")
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                mode = "client",
                routing = "single-page",
                flutter = "none",
                backend = "none",
                runPubGet = false
            )
        )

        // Verificar estructura básica
        assertBasicProjectStructure(projectDir, "jaspr_client_test")

        // Verificar contenido específico de modo client
        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain project name", pubspec.contains("name: jaspr_client_test"))
        assertTrue("pubspec.yaml should contain jaspr dependency", pubspec.contains("jaspr:"))

        println("✓ Client mode test passed")
        printProjectTree(projectDir)
    }

    fun testCreateProject_DocumentationTemplate() {
        val projectDir = createTestProjectDir("jaspr_docs_test")
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                template = "docs",
                mode = "static",
                routing = "multi-page",
                flutter = "none",
                backend = "none",
                runPubGet = false
            )
        )

        // Verificar estructura básica
        assertBasicProjectStructure(projectDir, "jaspr_docs_test")

        // Verificar que el nombre del proyecto fue reemplazado correctamente
        val pubspec = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain correct project name", pubspec.contains("name: jaspr_docs_test"))

        val readme = File(projectDir, "README.md").readText(StandardCharsets.UTF_8)
        assertFalse("README.md should not contain temp project name", readme.contains("_temp_"))

        println("✓ Documentation template test passed")
        printProjectTree(projectDir)
    }

    fun testProjectNameReplacement_AllFiles() {
        val projectDir = createTestProjectDir("my_renamed_project")
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                mode = "static",
                runPubGet = false
            )
        )

        // Verificar que el nombre del proyecto se reemplazó en todos los archivos clave
        val expectedName = "my_renamed_project"

        val pubspecContent = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue("pubspec.yaml should contain new project name", pubspecContent.contains("name: $expectedName"))
        assertFalse("pubspec.yaml should not contain temp name", pubspecContent.contains("_temp_"))

        val readmeContent = File(projectDir, "README.md").readText(StandardCharsets.UTF_8)
        assertFalse("README.md should not contain temp name", readmeContent.contains("_temp_"))

        val analysisContent = File(projectDir, "analysis_options.yaml").readText(StandardCharsets.UTF_8)
        // El analysis_options puede o no contener el nombre del proyecto, pero no debe tener _temp_
        assertFalse("analysis_options.yaml should not contain temp name", analysisContent.contains("_temp_"))

        println("✓ Project name replacement test passed")
    }

    fun testTempDirectoryCleanup() {
        val projectDir = createTestProjectDir("cleanup_test_project")
        val parentDir = projectDir.parentFile
        val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

        // Verificar que no hay directorios temporales antes
        val tempDirsBefore = parentDir.listFiles()?.filter { it.name.contains("_temp_") } ?: emptyList()

        creator.create(
            projectDir = projectDir,
            sdkPath = sdkPath,
            options = JasprProjectCreator.Options(
                mode = "static",
                runPubGet = false
            )
        )

        // Verificar que el proyecto se creó correctamente
        assertTrue("Project directory should exist", projectDir.exists())
        assertTrue("pubspec.yaml should exist", File(projectDir, "pubspec.yaml").exists())

        // Verificar que no quedaron directorios temporales
        val tempDirsAfter = parentDir.listFiles()?.filter {
            it.name.contains("_temp_") && it.name.startsWith("cleanup_test_project")
        } ?: emptyList()

        assertTrue(
            "No temporary directories should remain after project creation",
            tempDirsAfter.isEmpty()
        )

        println("✓ Temp directory cleanup test passed")
    }

    fun testCreateProject_WithMultipleOptionsVariations() {
        val testCases = listOf(
            Triple("static_multipage", "static", "multi-page"),
            Triple("static_singlepage", "static", "single-page"),
            Triple("server_multipage", "server", "multi-page"),
            Triple("client_singlepage", "client", "single-page")
        )

        testCases.forEach { (name, mode, routing) ->
            val projectDir = createTestProjectDir(name)
            val creator = JasprProjectCreator(cliRunner = DefaultCliRunner)

            creator.create(
                projectDir = projectDir,
                sdkPath = sdkPath,
                options = JasprProjectCreator.Options(
                    mode = mode,
                    routing = routing,
                    flutter = "none",
                    backend = "none",
                    runPubGet = false
                )
            )

            assertBasicProjectStructure(projectDir, name)
            println("✓ Test passed for: $name (mode=$mode, routing=$routing)")
        }
    }

    // ========== Helper Methods ==========

    private fun createTestProjectDir(name: String): File {
        val root = File(myFixture.tempDirPath)
        return File(root, name).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
    }

    private fun assertBasicProjectStructure(projectDir: File, expectedProjectName: String) {
        assertTrue("Project directory should exist", projectDir.exists())
        assertTrue("Project directory should be a directory", projectDir.isDirectory)

        // Archivos principales
        assertTrue("pubspec.yaml should exist", File(projectDir, "pubspec.yaml").exists())
        assertTrue("README.md should exist", File(projectDir, "README.md").exists())
        assertTrue("analysis_options.yaml should exist", File(projectDir, "analysis_options.yaml").exists())

        // Directorio lib
        val libDir = File(projectDir, "lib")
        assertTrue("lib directory should exist", libDir.exists())
        assertTrue("lib directory should be a directory", libDir.isDirectory)

        // Al menos un archivo .dart en lib
        val dartFiles = libDir.walkTopDown()
            .filter { it.isFile && it.extension == "dart" }
            .toList()
        assertTrue("Should have at least one .dart file in lib", dartFiles.isNotEmpty())

        // Verificar nombre del proyecto en pubspec.yaml
        val pubspecContent = File(projectDir, "pubspec.yaml").readText(StandardCharsets.UTF_8)
        assertTrue(
            "pubspec.yaml should contain correct project name",
            pubspecContent.contains("name: $expectedProjectName")
        )
    }

    private fun printProjectTree(dir: File, indent: String = "") {
        if (!dir.exists()) {
            println("$indent<does not exist> ${dir.absolutePath}")
            return
        }

        if (dir.isFile) {
            println("$indent${dir.name}")
            return
        }

        val children = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()

        println("${indent}${dir.name}/")

        children.forEachIndexed { index, child ->
            val isChildLast = index == children.lastIndex
            val branch = if (isChildLast) "└── " else "├── "
            val nextIndent = indent + if (isChildLast) "    " else "│   "

            print("$indent$branch")
            if (child.isDirectory) {
                println("${child.name}/")
                child.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEachIndexed { subIndex, subChild ->
                        val isSubLast = subIndex == (child.listFiles()?.size ?: 0) - 1
                        val subBranch = if (isSubLast) "└── " else "├── "
                        println("$nextIndent$subBranch${subChild.name}")
                    }
            } else {
                println(child.name)
            }
        }
    }
}