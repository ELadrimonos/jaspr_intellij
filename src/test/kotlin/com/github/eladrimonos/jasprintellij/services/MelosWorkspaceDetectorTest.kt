package com.github.eladrimonos.jasprintellij.services

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MelosWorkspaceDetectorTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("melos_detector_test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `detects pub workspace field in root pubspec`() {
        File(root, "pubspec.yaml").writeText(
            """
            name: root_workspace
            workspace:
              - packages/website
            """.trimIndent()
        )

        val info = MelosWorkspaceDetector.detect(root)

        assertNotNull(info)
        assertEquals(root, info!!.workspaceRoot)
        assertEquals(File(root, "pubspec.yaml"), info.melosConfigFile)
        assertEquals(listOf("packages/website"), info.memberPackagePaths)
        assertTrue(!info.isMelosManaged)
    }

    @Test
    fun `detects melos yaml file`() {
        File(root, "melos.yaml").writeText(
            """
            name: my_workspace
            packages:
              - packages/**
            """.trimIndent()
        )

        val info = MelosWorkspaceDetector.detect(root)

        assertNotNull(info)
        assertEquals(File(root, "melos.yaml"), info!!.melosConfigFile)
        assertTrue(info.isMelosManaged)
    }

    @Test
    fun `detects embedded melos section in root pubspec`() {
        File(root, "pubspec.yaml").writeText(
            """
            name: root_workspace
            melos:
              scripts:
                foo:
                  exec:
                    command: echo foo
            """.trimIndent()
        )

        val info = MelosWorkspaceDetector.detect(root)

        assertNotNull(info)
        assertEquals(File(root, "pubspec.yaml"), info!!.melosConfigFile)
        assertTrue(info.isMelosManaged)
    }

    @Test
    fun `walks up from nested member package to find melos yaml`() {
        File(root, "melos.yaml").writeText("name: my_workspace\n")
        val memberDir = File(root, "packages/website").apply { mkdirs() }
        File(memberDir, "pubspec.yaml").writeText("name: website\ndependencies:\n  jaspr: ^0.20.0\n")

        val info = MelosWorkspaceDetector.detect(memberDir)

        assertNotNull(info)
        assertEquals(root, info!!.workspaceRoot)
        assertEquals(File(root, "melos.yaml"), info.melosConfigFile)
    }

    @Test
    fun `plain project without workspace indicators returns null`() {
        File(root, "pubspec.yaml").writeText("name: plain_project\ndependencies:\n  jaspr: ^0.20.0\n")

        assertNull(MelosWorkspaceDetector.detect(root))
    }

    @Test
    fun `malformed yaml does not throw and returns null`() {
        File(root, "pubspec.yaml").writeText("name: broken\nworkspace: [unterminated\n  - foo")

        assertNull(MelosWorkspaceDetector.detect(root))
    }

    @Test
    fun `does not climb past the upward level limit`() {
        var dir = root
        repeat(6) { dir = File(dir, "level$it").apply { mkdirs() } }
        File(root, "melos.yaml").writeText("name: too_far\n")

        assertNull(MelosWorkspaceDetector.detect(dir))
    }
}
