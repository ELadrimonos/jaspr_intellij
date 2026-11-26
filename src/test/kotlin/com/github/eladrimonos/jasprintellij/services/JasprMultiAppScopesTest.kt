package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Scope data in a workspace with several Jaspr apps: each app's `scopes.json` must only
 * describe its own files, and files of a shared package must say which app each root is from.
 */
class JasprMultiAppScopesTest : BasePlatformTestCase() {

    private lateinit var service: JasprToolingDaemonService
    private lateinit var root: File
    private lateinit var web: File
    private lateinit var admin: File

    override fun setUp() {
        super.setUp()
        service = project.getService(JasprToolingDaemonService::class.java)
        service.stop()
        service.useFileSystemScopes = true
        root = FileUtil.createTempDirectory("jaspr-workspace", null, true)
        web = File(root, "apps/web").apply { mkdirs() }
        admin = File(root, "apps/admin").apply { mkdirs() }
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

    fun testEachAppKeepsItsOwnFiles() {
        val webApp = dartFile(web, "lib/app.dart")
        val adminApp = dartFile(admin, "lib/app.dart")
        writeScopes(web, listOf(Entry(webApp, "WebApp", server = webApp)))
        writeScopes(admin, listOf(Entry(adminApp, "AdminApp", client = adminApp)))

        service.refreshApps(listOf(web, admin))

        val webScopes = service.getScopesForFile(webApp.path)!!
        assertEquals(listOf("WebApp"), webScopes.components)
        assertNull("A file inside an app is not tagged with its app", webScopes.serverScopeRoots.single().app)
        assertEquals(listOf("AdminApp"), service.getScopesForFile(adminApp.path)!!.components)
    }

    fun testFileInsideAnAppIgnoresOtherAppsData() {
        val webPage = dartFile(web, "lib/page.dart")
        // admin's scopes.json mentions a web file (e.g. through a path dependency on the app).
        writeScopes(web, emptyList())
        writeScopes(admin, listOf(Entry(webPage, "Page", client = webPage)))

        service.refreshApps(listOf(web, admin))

        assertNull(service.getScopesForFile(webPage.path))
    }

    fun testFileInsideAnAppThatIsNotRunningGetsNoData() {
        val webPage = dartFile(web, "lib/page.dart")
        // Only admin has been served; web has no scopes.json yet.
        writeScopes(admin, listOf(Entry(webPage, "Page", client = webPage)))

        service.refreshApps(listOf(web, admin))

        assertNull(service.getScopesForFile(webPage.path))
    }

    fun testSharedPackageTagsEachRootWithItsApp() {
        val button = dartFile(File(root, "packages/ui"), "lib/button.dart")
        val webApp = dartFile(web, "lib/app.dart")
        val adminApp = dartFile(admin, "lib/app.dart")
        writeScopes(web, listOf(Entry(button, "Button", client = webApp)))
        writeScopes(admin, listOf(Entry(button, "Button", server = adminApp)))

        service.refreshApps(listOf(web, admin))

        val scopes = service.getScopesForFile(button.path)!!
        assertEquals(listOf("Button"), scopes.components)
        assertEquals(listOf("web" to canonical(webApp)), scopes.clientScopeRoots.map { it.app to it.path })
        assertEquals(listOf("admin" to canonical(adminApp)), scopes.serverScopeRoots.map { it.app to it.path })
    }

    fun testSharedPackageIsTaggedEvenWhenOnlyOneAppHasData() {
        val button = dartFile(File(root, "packages/ui"), "lib/button.dart")
        val webApp = dartFile(web, "lib/app.dart")
        writeScopes(web, listOf(Entry(button, "Button", client = webApp)))

        service.refreshApps(listOf(web, admin))

        assertEquals(listOf("web"), service.getScopesForFile(button.path)!!.clientScopeRoots.map { it.app })
    }

    fun testRemovingOneAppsScopesFileKeepsTheOther() {
        val webApp = dartFile(web, "lib/app.dart")
        val adminApp = dartFile(admin, "lib/app.dart")
        val webScopes = writeScopes(web, listOf(Entry(webApp, "WebApp", server = webApp)))
        writeScopes(admin, listOf(Entry(adminApp, "AdminApp", server = adminApp)))
        service.refreshApps(listOf(web, admin))

        webScopes.delete()
        service.pollScopesFiles()

        assertNull(service.getScopesForFile(webApp.path))
        assertNotNull(service.getScopesForFile(adminApp.path))
    }

    fun testAppRemovedFromWorkspaceDropsItsData() {
        val adminApp = dartFile(admin, "lib/app.dart")
        writeScopes(admin, listOf(Entry(adminApp, "AdminApp", server = adminApp)))
        service.refreshApps(listOf(web, admin))
        assertNotNull(service.getScopesForFile(adminApp.path))

        service.refreshApps(listOf(web))

        assertNull(service.getScopesForFile(adminApp.path))
    }

    fun testRelativePathsResolveAgainstTheirOwnApp() {
        val adminApp = dartFile(admin, "lib/app.dart")
        writeScopes(admin, listOf(Entry(adminApp, "AdminApp", server = adminApp)), relative = true)

        service.refreshApps(listOf(web, admin))

        val scopes = service.getScopesForFile(adminApp.path)!!
        assertEquals(canonical(adminApp), scopes.serverScopeRoots.single().path)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private data class Entry(val file: File, val component: String, val server: File? = null, val client: File? = null)

    private fun dartFile(packageDir: File, path: String): File =
        File(packageDir, path).apply {
            parentFile.mkdirs()
            writeText("// $path\n")
        }

    private fun canonical(file: File): String = file.canonicalPath.replace("\\", "/")

    /** Writes [app]'s `.dart_tool/jaspr/scopes.json` in the jaspr_cli (>= 0.23) format. */
    private fun writeScopes(app: File, entries: List<Entry>, relative: Boolean = false): File {
        fun pathOf(file: File) =
            if (relative) file.canonicalFile.relativeTo(app.canonicalFile).invariantSeparatorsPath else canonical(file)

        val locations = linkedMapOf<String, Any>()
        val scopes = linkedMapOf<String, Any>()
        entries.forEachIndexed { i, entry ->
            fun location(id: String, file: File, name: String): String {
                locations[id] = mapOf("path" to pathOf(file), "name" to name, "line" to 1, "char" to 0, "length" to name.length)
                return id
            }
            val component = location("c$i", entry.file, entry.component)
            scopes[pathOf(entry.file)] = mapOf(
                "components" to listOf(component),
                "serverScopeRoots" to listOfNotNull(entry.server?.let { location("s$i", it, "Root") }),
                "clientScopeRoots" to listOfNotNull(entry.client?.let { location("k$i", it, "Root") }),
            )
        }
        val json = com.google.gson.Gson().toJson(mapOf("locations" to locations, "scopes" to scopes))
        return File(app, ".dart_tool/jaspr/scopes.json").apply {
            parentFile.mkdirs()
            writeText(json)
        }
    }
}
