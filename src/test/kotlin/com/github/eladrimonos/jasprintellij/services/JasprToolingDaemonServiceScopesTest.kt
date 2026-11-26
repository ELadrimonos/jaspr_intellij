package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for the scope-data handling of [JasprToolingDaemonService]: loading `scopes.json`
 * must replace the previous data atomically, so concurrent readers (CodeVision providers on
 * background threads) never observe an empty or partially filled map mid-reload.
 */
class JasprToolingDaemonServiceScopesTest : BasePlatformTestCase() {

    private lateinit var service: JasprToolingDaemonService
    private lateinit var tempDir: File

    override fun setUp() {
        super.setUp()
        service = project.getService(JasprToolingDaemonService::class.java)
        service.stop()
        tempDir = FileUtil.createTempDirectory("jaspr-scopes", null, true)
    }

    override fun tearDown() {
        try {
            service.stop()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testLoadScopesFromFileExposesComponents() {
        val app = dartFile("app.dart")
        val scopesJson = writeScopesJson(
            "scopes.json",
            mapOf(app to listOf("App", "Header")),
        )

        service.loadScopesFromFile(scopesJson)

        val scopes = service.getScopesForFile(app.path)
        assertNotNull("Scopes must be available for ${app.path} after loading", scopes)
        assertEquals(listOf("App", "Header"), scopes!!.components)
        assertEquals(1, scopes.serverScopeRoots.size)
        assertEquals(canonical(app), scopes.serverScopeRoots[0].path)
        assertEquals(3, scopes.serverScopeRoots[0].line)
        assertEquals(6, scopes.serverScopeRoots[0].character)
        assertTrue(scopes.clientScopeRoots.isEmpty())
    }

    fun testReloadReplacesPreviousEntriesInsteadOfMerging() {
        val app = dartFile("app.dart")
        val other = dartFile("other.dart")

        service.loadScopesFromFile(writeScopesJson("first.json", mapOf(app to listOf("App"))))
        assertNotNull(service.getScopesForFile(app.path))

        service.loadScopesFromFile(writeScopesJson("second.json", mapOf(other to listOf("Other"))))

        assertNull("Entries from the previous load must be gone after a reload", service.getScopesForFile(app.path))
        assertEquals(listOf("Other"), service.getScopesForFile(other.path)?.components)
    }

    fun testStopClearsScopes() {
        val app = dartFile("app.dart")
        service.loadScopesFromFile(writeScopesJson("scopes.json", mapOf(app to listOf("App"))))
        assertNotNull(service.getScopesForFile(app.path))

        service.stop()

        assertNull(service.getScopesForFile(app.path))
    }

    fun testConcurrentReadersNeverSeeEmptyOrPartialScopesDuringReloads() {
        val app = dartFile("app.dart")
        // Many files per load so a non-atomic clear-then-refill would leave a visible window.
        val extraFiles = (1..50).map { dartFile("extra_$it.dart") }
        val last = extraFiles.last()

        val contentA = linkedMapOf(app to listOf("A1", "A2")).apply {
            extraFiles.forEach { put(it, listOf("ExtraA")) }
        }
        val contentB = linkedMapOf(app to listOf("B1", "B2", "B3")).apply {
            extraFiles.forEach { put(it, listOf("ExtraB")) }
        }
        val fileA = writeScopesJson("a.json", contentA)
        val fileB = writeScopesJson("b.json", contentB)

        service.loadScopesFromFile(fileA)

        val validComponents = setOf(listOf("A1", "A2"), listOf("B1", "B2", "B3"))
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val reads = AtomicInteger(0)

        val reader = Thread({
            try {
                while (running.get()) {
                    val appScopes = service.getScopesForFile(app.path)
                        ?: error("getScopesForFile(app) returned null mid-reload")
                    if (appScopes.components !in validComponents) {
                        error("Unexpected components for app: ${appScopes.components}")
                    }
                    // The last file inserted must always be present too: a partially filled
                    // map would contain app.dart but not yet the last entry.
                    service.getScopesForFile(last.path)
                        ?: error("getScopesForFile(last) returned null mid-reload")
                    reads.incrementAndGet()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "JasprScopesReaderTest")
        reader.isDaemon = true
        reader.start()

        try {
            repeat(200) { i ->
                service.loadScopesFromFile(if (i % 2 == 0) fileB else fileA)
                failure.get()?.let { throw AssertionError("Reader failed during reload #$i", it) }
            }
        } finally {
            running.set(false)
            reader.join(10_000)
        }

        failure.get()?.let { throw AssertionError("Reader observed an inconsistent scope map", it) }
        assertTrue("Reader thread should have performed reads", reads.get() > 0)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun dartFile(name: String): File =
        File(tempDir, "lib/$name").apply {
            parentFile.mkdirs()
            writeText("// $name\n")
        }

    private fun canonical(file: File): String = file.canonicalPath.replace("\\", "/")

    /**
     * Writes a `scopes.json` in the format produced by the Jaspr CLI (>= 0.23). Each file gets
     * one location per component; the first component is used as the server scope root.
     */
    private fun writeScopesJson(name: String, filesToComponents: Map<File, List<String>>): File {
        val locations = linkedMapOf<String, Any>()
        val scopes = linkedMapOf<String, Any>()

        filesToComponents.entries.forEachIndexed { fileIndex, (file, components) ->
            val path = canonical(file)
            val ids = components.mapIndexed { componentIndex, componentName ->
                val id = "f${fileIndex}_c$componentIndex"
                locations[id] = mapOf(
                    "path" to path,
                    "name" to componentName,
                    "line" to 3,
                    "char" to 6,
                    "length" to componentName.length,
                )
                id
            }
            scopes[path] = mapOf(
                "components" to ids,
                "serverScopeRoots" to ids.take(1),
                "clientScopeRoots" to emptyList<String>(),
            )
        }

        val json = com.google.gson.Gson().toJson(mapOf("locations" to locations, "scopes" to scopes))
        return File(tempDir, name).apply { writeText(json) }
    }

    fun testChangedScopePaths() {
        val svc = project.getService(JasprToolingDaemonService::class.java)
        val a = JasprToolingDaemonService.FileComponentScopes(listOf("App"), emptyList(), emptyList())
        val b = JasprToolingDaemonService.FileComponentScopes(listOf("Other"), emptyList(), emptyList())

        // Identical reload (the common case on every rebuild) changes nothing.
        assertEmpty(svc.changedScopePaths(mapOf("/x.dart" to a), mapOf("/x.dart" to a.copy())))

        assertEquals(
            setOf("/changed.dart", "/added.dart", "/removed.dart"),
            svc.changedScopePaths(
                mapOf("/same.dart" to a, "/changed.dart" to a, "/removed.dart" to a),
                mapOf("/same.dart" to a, "/changed.dart" to b, "/added.dart" to b),
            ),
        )
    }

    fun testParseConvertHtmlOutputIgnoresUpdateBanner() {
        val svc = project.getService(JasprToolingDaemonService::class.java)
        val stdout = """
            |╔══════════════════════════════════╗
            |║  Update available! 0.23.4 → 0.23.5 ║
            |╚══════════════════════════════════╝
            |{"result":".fragment([\n  h1([\n    .text('Hola'),\n  ]),\n])"}
            |""".trimMargin()

        assertEquals(".fragment([\n  h1([\n    .text('Hola'),\n  ]),\n])", svc.parseConvertHtmlOutput(stdout))
        assertNull(svc.parseConvertHtmlOutput("no json here"))
        assertNull(svc.parseConvertHtmlOutput("{\"result\":\"\"}"))
    }
}
