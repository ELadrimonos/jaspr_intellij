package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.JasprIntegrationTestCase
import com.github.eladrimonos.jasprintellij.services.DefaultCliRunner
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.github.eladrimonos.jasprintellij.services.MelosScriptInjector
import com.github.eladrimonos.jasprintellij.services.MelosWorkspaceDetector
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage for the melos/workspace support added for
 * https://github.com/ELadrimonos/jaspr_intellij/issues/20: scaffolds a real Jaspr
 * package inside a Dart/Melos workspace (root pubspec.yaml with `workspace:` +
 * `melos:`, matching Melos 8.x's pub-workspaces-based model), injects the
 * auto-generated script, and runs it via a real `melos run` invocation to confirm
 * the daemon protocol still reaches [JasprDaemonProcessHandler] the same way it
 * does outside a workspace.
 */
class JasprMelosIntegrationTest : JasprIntegrationTestCase() {

    override fun setUp() {
        super.setUp()
        installMelos()
    }

    fun testWorkspaceDetectionAndMelosRunReachesDaemon() {
        val workspaceRoot = createTestProjectDir("melos_workspace_test")
        val memberDir = File(workspaceRoot, "packages/website").apply { mkdirs() }

        createProject(memberDir, JasprProjectCreator.Options(mode = "static", runPubGet = false))
        assertBasicProjectStructure(memberDir, "website")

        // Same build_web_compilers/analyzer conflict workaround as JasprVersionComparisonTest.
        val memberPubspec = File(memberDir, "pubspec.yaml")
        memberPubspec.writeText(
            memberPubspec.readText().replace("build_web_compilers: ^4.8.10", "build_web_compilers: ^4.8.5")
        )
        // Opt this member into the workspace, as `dart pub` requires.
        memberPubspec.appendText("\nresolution: workspace\n")

        // Root pubspec.yaml declaring a Dart pub workspace — the format Melos 8.x
        // (`dart pub global activate melos`) actually expects; standalone melos.yaml
        // files are no longer recognized by current Melos releases.
        File(workspaceRoot, "pubspec.yaml").writeText(
            """
            name: melos_workspace_test
            environment:
              sdk: ^3.13.0
            dev_dependencies:
              melos: ^8.5.0
            workspace:
              - packages/website
            """.trimIndent()
        )

        // 1. Detection: opening the member package should resolve back to the workspace root.
        val info = MelosWorkspaceDetector.detect(memberDir)
        assertNotNull("Workspace should be detected from the member package directory", info)
        assertEquals(workspaceRoot.canonicalFile, info!!.workspaceRoot.canonicalFile)
        assertEquals(File(workspaceRoot, "pubspec.yaml").canonicalFile, info.melosConfigFile.canonicalFile)

        // 2. Injection: write the delimited script block into the root pubspec.yaml.
        val scriptName = "serve_jaspr_test"
        MelosScriptInjector.ensureScript(info.melosConfigFile, scriptName, "website")
        val rootPubspecText = info.melosConfigFile.readText()
        assertTrue(rootPubspecText.contains(MelosScriptInjector.MARKER_START))
        assertTrue(rootPubspecText.contains("$scriptName:"))

        // Re-running the injection must be a no-op (idempotency), matching subtask 2 of the issue.
        val secondResult = MelosScriptInjector.ensureScript(info.melosConfigFile, scriptName, "website")
        assertFalse(
            "Second injection with identical arguments should not modify the file",
            (secondResult as MelosScriptInjector.InjectionResult.Written).changed
        )

        // 3. `pub get` from the workspace root resolves all members at once.
        val dartExe = File(sdkPath, "bin/${if (SystemInfo.isWindows) "dart.exe" else "dart"}").absolutePath
        val pubGetCmd = GeneralCommandLine(dartExe, "pub", "get")
            .withWorkDirectory(workspaceRoot)
            .withCharset(StandardCharsets.UTF_8)
        val pubGetResult = DefaultCliRunner.run(pubGetCmd)
        assertTrue("pub get failed for melos workspace: ${pubGetResult.stderr}", pubGetResult.exitCode == 0)

        // 4. Real execution: `melos run <script>` from the workspace root must still reach
        //    the daemon protocol JasprDaemonProcessHandler already knows how to parse.
        val melosRunCmd = GeneralCommandLine(dartExe, "pub", "global", "run", "melos", "run", scriptName)
            .withWorkDirectory(workspaceRoot)
            .withCharset(StandardCharsets.UTF_8)

        val latch = CountDownLatch(1)
        var serverUri: String? = null
        val handler = JasprDaemonProcessHandler(melosRunCmd, onServerStarted = { uri ->
            serverUri = uri
            latch.countDown()
        })
        handler.startNotify()

        try {
            val started = latch.await(90, TimeUnit.SECONDS)
            assertTrue("Daemon started via 'melos run $scriptName' did not report a VM Service URI within 90s", started)
            assertNotNull("VM Service URI should not be null", serverUri)
        } finally {
            handler.destroyProcess()
        }
    }
}
