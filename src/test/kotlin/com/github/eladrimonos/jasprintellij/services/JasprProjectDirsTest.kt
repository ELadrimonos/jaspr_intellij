package com.github.eladrimonos.jasprintellij.services

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class JasprProjectDirsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("jaspr_project_dirs_test").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun pkg(path: String, jaspr: Boolean) {
        File(root, path).mkdirs()
        val deps = if (jaspr) "  jaspr: ^0.23.0\n" else "  path: ^1.9.0\n"
        File(root, "$path/pubspec.yaml").writeText("name: ${path.substringAfterLast('/')}\nresolution: workspace\ndependencies:\n$deps")
    }

    @Test
    fun `finds every jaspr app of a plain pub workspace without melos`() {
        pkg("apps/web", jaspr = true)
        pkg("apps/admin", jaspr = true)
        pkg("packages/utils", jaspr = false)
        File(root, "pubspec.yaml").writeText("name: ws\nworkspace:\n  - apps/*\n  - packages/utils\n")

        assertEquals(
            listOf(File(root, "apps/admin"), File(root, "apps/web")),
            JasprTooling.findJasprProjectDirs(root),
        )
    }

    @Test
    fun `finds the opened member package when the IDE opens it instead of the workspace root`() {
        pkg("apps/web", jaspr = true)
        pkg("apps/admin", jaspr = true)
        File(root, "pubspec.yaml").writeText("name: ws\nworkspace:\n  - apps/web\n  - apps/admin\n")

        // The member itself depends on jaspr, then the workspace found by walking up adds its siblings.
        assertEquals(
            listOf(File(root, "apps/web"), File(root, "apps/admin")),
            JasprTooling.findJasprProjectDirs(File(root, "apps/web")),
        )
    }

    @Test
    fun `single jaspr project without workspace`() {
        File(root, ".git").mkdirs()
        File(root, "pubspec.yaml").writeText("name: site\ndependencies:\n  jaspr: ^0.23.0\n")

        assertEquals(listOf(root), JasprTooling.findJasprProjectDirs(root))
    }
}
