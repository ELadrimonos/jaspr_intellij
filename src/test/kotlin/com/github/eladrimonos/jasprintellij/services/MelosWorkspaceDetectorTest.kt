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

    @Test
    fun `resolves a member package dir by name relative to the config file`() {
        File(root, "pubspec.yaml").writeText("name: ws\nworkspace:\n  - apps/site\n  - packages/ui\n")
        File(root, "apps/site").mkdirs()
        File(root, "apps/site/pubspec.yaml").writeText("name: website\n")
        File(root, "packages/ui").mkdirs()
        File(root, "packages/ui/pubspec.yaml").writeText("name: ui_kit\n")

        val info = MelosWorkspaceDetector.detect(root)!!

        assertEquals("apps/site", MelosWorkspaceDetector.resolveMemberPackageRelativeDir(info, "website"))
        assertEquals("packages/ui", MelosWorkspaceDetector.resolveMemberPackageRelativeDir(info, "ui_kit"))
        assertNull(MelosWorkspaceDetector.resolveMemberPackageRelativeDir(info, "missing"))
    }

    private fun pkg(path: String) {
        File(root, path).mkdirs()
        File(root, "$path/pubspec.yaml").writeText("name: ${path.substringAfterLast('/')}\n")
    }

    @Test
    fun `expands glob entries of the pub workspace field`() {
        pkg("apps/web")
        pkg("apps/admin")
        pkg("packages/ui")
        pkg("packages/nested/forms")
        File(root, "packages/not_a_package").mkdirs()
        File(root, "pubspec.yaml").writeText("name: ws\nworkspace:\n  - apps/*\n  - packages/**\n  - tools/cli\n")

        val info = MelosWorkspaceDetector.detect(root)!!

        assertEquals(
            listOf("apps/admin", "apps/web", "packages/nested/forms", "packages/ui", "tools/cli"),
            info.memberPackagePaths,
        )
    }

    @Test
    fun `reads members from melos 6 packages globs without a pub workspace`() {
        pkg("apps/web")
        pkg("packages/ui")
        File(root, "melos.yaml").writeText("name: ws\npackages:\n  - apps/*\n")

        val info = MelosWorkspaceDetector.detect(root)!!

        assertEquals(listOf("apps/web"), info.memberPackagePaths)
    }

    @Test
    fun `glob expansion skips build output and hidden directories`() {
        pkg("apps/web")
        pkg("apps/web/build/generated")
        pkg("apps/.hidden")

        assertEquals(listOf("apps/web"), MelosWorkspaceDetector.expandMemberPatterns(root, listOf("apps/**")))
    }
}
