package com.github.eladrimonos.jasprintellij.services

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MelosScriptInjectorTest {

    private lateinit var dir: File
    private lateinit var target: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("melos_injector_test").toFile()
        target = File(dir, "melos.yaml")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `injects block into empty file`() {
        val result = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertTrue(result is MelosScriptInjector.InjectionResult.Written)
        assertTrue((result as MelosScriptInjector.InjectionResult.Written).changed)
        val text = target.readText()
        assertTrue(text.contains(MelosScriptInjector.MARKER_START))
        assertTrue(text.contains(MelosScriptInjector.MARKER_END))
        assertTrue(text.contains("serve_jaspr:"))
        assertTrue(text.contains("scope: website"))
        assertEquals(listOf("serve_jaspr"), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `appends a fresh melos section without touching existing content`() {
        target.writeText("name: my_workspace\npackages:\n  - packages/**\n")

        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        val text = target.readText()
        assertTrue(text.startsWith("name: my_workspace\npackages:\n  - packages/**\n"))
        assertTrue(text.contains(MelosScriptInjector.MARKER_START))
    }

    @Test
    fun `existing user-authored melos scripts are preserved when adding our own`() {
        target.writeText(
            """
            melos:
              scripts:
                custom:
                  exec:
                    command: echo hi
            """.trimIndent()
        )

        val result = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertTrue(result is MelosScriptInjector.InjectionResult.Written)
        val text = target.readText()
        assertTrue("user's own script preserved", text.contains("custom:"))
        assertTrue("user's own script command preserved", text.contains("command: echo hi"))
        assertTrue("new script added", text.contains("serve_jaspr:"))
        assertTrue("new script's package present", text.contains("scope: website"))
        assertEquals(listOf("custom", "serve_jaspr"), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `melos key without a scripts child gets one added`() {
        target.writeText("melos:\n  something_else: true\n")

        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        val text = target.readText()
        assertTrue("unrelated melos key preserved", text.contains("something_else: true"))
        assertTrue(text.contains("scripts:"))
        assertTrue(text.contains("serve_jaspr:"))
        assertEquals(listOf("serve_jaspr"), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `malformed top-level melos key is refused rather than corrupted`() {
        target.writeText("melos: \"not a mapping\"\n")

        val result = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertTrue(result is MelosScriptInjector.InjectionResult.ConflictExistingMelosSection)
        assertEquals("melos: \"not a mapping\"\n", target.readText())
    }

    @Test
    fun `malformed scripts value is refused rather than corrupted`() {
        target.writeText("melos:\n  scripts: \"not a mapping\"\n")

        val result = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertTrue(result is MelosScriptInjector.InjectionResult.ConflictExistingMelosSection)
        assertEquals("melos:\n  scripts: \"not a mapping\"\n", target.readText())
    }

    @Test
    fun `adding a second script preserves the first one (multiple Jaspr apps in one monorepo)`() {
        val prefix = "name: my_workspace\n"
        val suffix = "\nsome_other_key: value\n"
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")
        val withPrefix = prefix + target.readText() + suffix
        target.writeText(withPrefix)

        MelosScriptInjector.ensureScript(target, "serve_admin", "admin_panel")

        val text = target.readText()
        assertTrue("prefix preserved", text.startsWith(prefix))
        assertTrue("suffix preserved", text.endsWith(suffix))
        assertTrue("new script present", text.contains("serve_admin:"))
        assertTrue("old script still present", text.contains("serve_jaspr:"))
        assertTrue("old script's package still present", text.contains("scope: website"))
        assertTrue("new script's package present", text.contains("scope: admin_panel"))
        assertEquals("markers appear exactly once", 1, Regex(Regex.escape(MelosScriptInjector.MARKER_START)).findAll(text).count())
        assertEquals(
            "listScriptNames reflects both entries",
            listOf("serve_admin", "serve_jaspr"),
            MelosScriptInjector.listScriptNames(target)
        )
    }

    @Test
    fun `re-adding an existing script updates its package instead of duplicating it`() {
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")
        MelosScriptInjector.ensureScript(target, "serve_admin", "admin_panel")

        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website_v2")

        val text = target.readText()
        assertFalse("stale package value removed", text.contains("scope: website\n"))
        assertTrue("updated package present", text.contains("scope: website_v2"))
        assertTrue("unrelated script untouched", text.contains("scope: admin_panel"))
        assertEquals(listOf("serve_admin", "serve_jaspr"), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `second identical call does not modify the file`() {
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")
        val mtimeAfterFirst = target.lastModified()
        Thread.sleep(50)

        val result = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertTrue(result is MelosScriptInjector.InjectionResult.Written)
        assertFalse((result as MelosScriptInjector.InjectionResult.Written).changed)
        assertEquals(mtimeAfterFirst, target.lastModified())
    }

    @Test
    fun `truncated block without end marker is left alone and a new block is appended`() {
        target.writeText("${MelosScriptInjector.MARKER_START}\nmelos:\n  scripts:\n    broken:\n")

        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        val text = target.readText()
        assertTrue("orphan start marker preserved", text.contains("broken:"))
        assertEquals(
            "start marker appears twice: once orphaned, once in the new block",
            2,
            Regex(Regex.escape(MelosScriptInjector.MARKER_START)).findAll(text).count()
        )
        assertTrue("new script added alongside the orphan", text.contains("serve_jaspr:"))
    }

    @Test
    fun `listScriptNames is empty when no melos section exists`() {
        target.writeText("name: my_workspace\n")
        assertEquals(emptyList<String>(), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `listScriptNames returns both plugin-managed and user-authored scripts`() {
        target.writeText(
            """
            melos:
              scripts:
                bootstrap:
                  exec:
                    command: echo bootstrap
            """.trimIndent()
        )
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")

        assertEquals(listOf("bootstrap", "serve_jaspr"), MelosScriptInjector.listScriptNames(target))
    }

    @Test
    fun `migrates legacy packageName filter to scope`() {
        val legacy = """
            |name: my_workspace
            |melos:
            |  scripts:
            |    lint: dart analyze
            |    ${MelosScriptInjector.MARKER_START}
            |    serve_jaspr:
            |      description: "Jaspr Daemon using Melos (auto-generated by Jaspr IntelliJ Plugin)"
            |      exec:
            |        command: dart pub global run jaspr_cli:jaspr daemon
            |      packageFilters:
            |        packageName: website
            |    ${MelosScriptInjector.MARKER_END}
            |""".trimMargin()
        target.writeText(legacy)

        assertTrue(MelosScriptInjector.migrateLegacyFilters(target))

        val text = target.readText()
        assertTrue(text.contains("        scope: website\n"))
        assertFalse(text.contains("packageName:"))
        assertTrue("unrelated script untouched", text.contains("    lint: dart analyze\n"))
        assertEquals(listOf("lint", "serve_jaspr"), MelosScriptInjector.listScriptNames(target))

        assertFalse("second run is a no-op", MelosScriptInjector.migrateLegacyFilters(target))
    }

    @Test
    fun `migration ignores files without a managed block`() {
        target.writeText("melos:\n  scripts:\n    x:\n      packageFilters:\n        packageName: a\n")
        assertFalse(MelosScriptInjector.migrateLegacyFilters(target))
    }

    @Test
    fun `ensureScript also upgrades legacy entries it merges`() {
        target.writeText(
            "melos:\n  scripts:\n    ${MelosScriptInjector.MARKER_START}\n    old:\n" +
                "      packageFilters:\n        packageName: website\n    ${MelosScriptInjector.MARKER_END}\n"
        )
        MelosScriptInjector.ensureScript(target, "admin", "admin_panel")

        val text = target.readText()
        assertTrue(text.contains("scope: website"))
        assertTrue(text.contains("scope: admin_panel"))
        assertFalse(text.contains("packageName:"))
    }

    @Test
    fun `relativeDir generates a run script with inherited stdio`() {
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website", relativeDir = "apps/website")

        val text = target.readText()
        assertTrue(text.contains("run: 'cd \"apps/website\" && dart pub global run jaspr_cli:jaspr daemon'"))
        assertTrue(text.contains("stdio: inherit"))
        assertTrue(text.contains("scope: website"))
        assertFalse(text.contains("exec:"))
        assertTrue(MelosScriptInjector.usesInheritedStdio(target, "serve_jaspr"))

        val again = MelosScriptInjector.ensureScript(target, "serve_jaspr", "website", relativeDir = "apps/website")
        assertFalse("idempotent", (again as MelosScriptInjector.InjectionResult.Written).changed)
    }

    @Test
    fun `exec scripts don't use inherited stdio`() {
        MelosScriptInjector.ensureScript(target, "serve_jaspr", "website")
        assertFalse(MelosScriptInjector.usesInheritedStdio(target, "serve_jaspr"))
        assertFalse(MelosScriptInjector.usesInheritedStdio(target, "unknown"))
    }

    @Test
    fun `merging keeps the run form of existing entries`() {
        MelosScriptInjector.ensureScript(target, "a", "website", relativeDir = "apps/website")
        MelosScriptInjector.ensureScript(target, "b", "admin_panel")

        assertTrue(MelosScriptInjector.usesInheritedStdio(target, "a"))
        assertFalse(MelosScriptInjector.usesInheritedStdio(target, "b"))
    }

    @Test
    fun `migration upgrades exec entries when the package dir resolves`() {
        MelosScriptInjector.ensureScript(target, "a", "website")
        MelosScriptInjector.ensureScript(target, "b", "unknown_pkg")

        val resolver: (String) -> String? = { name -> if (name == "website") "apps/website" else null }
        val changed = MelosScriptInjector.migrateManagedBlock(target, resolver)

        assertTrue(changed)
        assertTrue(MelosScriptInjector.usesInheritedStdio(target, "a"))
        assertFalse("unresolvable package keeps exec", MelosScriptInjector.usesInheritedStdio(target, "b"))
        assertFalse("second run is a no-op", MelosScriptInjector.migrateManagedBlock(target, resolver))
    }

    @Test
    fun `migration without resolver leaves up-to-date exec entries alone`() {
        MelosScriptInjector.ensureScript(target, "a", "website")
        assertFalse(MelosScriptInjector.migrateManagedBlock(target, null))
    }
}
