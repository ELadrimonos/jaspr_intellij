package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprPackageDir
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class JasprPackageDirTest {

    // A real absolute path on every OS ("/work/ws" isn't absolute on Windows).
    private val root = Files.createTempDirectory("jaspr_package_dir_test").toFile().absoluteFile

    @Test
    fun `chosen package is resolved against the project root`() {
        assertEquals(File(root, "apps/web"), JasprPackageDir.resolve(root, "apps/web") { error("not used") })
    }

    @Test
    fun `no chosen package falls back to the first jaspr package, then the root`() {
        assertEquals(File(root, "apps/admin"), JasprPackageDir.resolve(root, "") { File(root, "apps/admin") })
        assertEquals(root, JasprPackageDir.resolve(root, "") { null })
    }

    @Test
    fun `stores package directories relative to the project root`() {
        assertEquals("apps/web", JasprPackageDir.toStored(root, File(root, "apps/web")))
    }

    @Test
    fun `rebases project-relative paths onto the package`() {
        val web = File(root, "apps/web")
        assertEquals("lib/main.server.dart", JasprPackageDir.rebase(root, web, "apps/web/lib/main.server.dart"))
        assertEquals("lib/main.server.dart", JasprPackageDir.rebase(root, web, File(web, "lib/main.server.dart").path))
    }

    @Test
    fun `files outside the package are passed as absolute paths`() {
        val web = File(root, "apps/web")
        assertEquals(File(root, "config/defines.json").absolutePath, JasprPackageDir.rebase(root, web, "config/defines.json"))
    }

    @Test
    fun `paths are unchanged when the package is the project root`() {
        assertEquals("lib/main.server.dart", JasprPackageDir.rebase(root, root, "lib/main.server.dart"))
    }
}
