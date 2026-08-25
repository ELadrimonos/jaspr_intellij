package com.github.eladrimonos.jasprintellij.services

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MelosPathResolverTest {

    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        workspaceRoot = Files.createTempDirectory("melos_path_resolver_test").toFile()
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    @Test
    fun `rebases a workspace-root-relative path to the package it actually lives in`() {
        val packageDir = File(workspaceRoot, "apps/website").apply { mkdirs() }
        File(packageDir, "pubspec.yaml").writeText("name: website\n")
        File(packageDir, "lib").mkdirs()

        val result = MelosPathResolver.rebaseRelativeToPackage(
            workspaceRoot.absolutePath, "apps/website/lib/main.server.dart",
        )

        assertEquals("lib/main.server.dart", result)
    }

    @Test
    fun `leaves an already package-relative path unchanged`() {
        val packageDir = File(workspaceRoot, "apps/website").apply { mkdirs() }
        File(packageDir, "pubspec.yaml").writeText("name: website\n")

        // basePath is itself the package dir here (project opened directly on the member package).
        val result = MelosPathResolver.rebaseRelativeToPackage(packageDir.absolutePath, "lib/main.server.dart")

        assertEquals("lib/main.server.dart", result)
    }

    @Test
    fun `resolves nested package paths deeper than one level`() {
        val packageDir = File(workspaceRoot, "packages/apps/website").apply { mkdirs() }
        File(packageDir, "pubspec.yaml").writeText("name: website\n")

        val result = MelosPathResolver.rebaseRelativeToPackage(
            workspaceRoot.absolutePath, "packages/apps/website/lib/src/main.server.dart",
        )

        assertEquals("lib/src/main.server.dart", result)
    }

    @Test
    fun `falls back to the raw path when no pubspec_yaml is found above it`() {
        val result = MelosPathResolver.rebaseRelativeToPackage(workspaceRoot.absolutePath, "lib/main.server.dart")

        assertEquals("lib/main.server.dart", result)
    }

    @Test
    fun `resolves an already-absolute path the same way`() {
        val packageDir = File(workspaceRoot, "apps/website").apply { mkdirs() }
        File(packageDir, "pubspec.yaml").writeText("name: website\n")
        val absoluteInput = File(packageDir, "lib/main.server.dart").absolutePath

        val result = MelosPathResolver.rebaseRelativeToPackage(workspaceRoot.absolutePath, absoluteInput)

        assertEquals("lib/main.server.dart", result)
    }
}
