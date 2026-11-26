package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprDaemonProcessHandlerTest : BasePlatformTestCase() {

    private fun createHandler(
        onServerStarted: (String) -> Unit = {},
        onClientDebugPort: (String) -> Unit = {},
        onOutput: (String, ConsoleViewContentType) -> Unit = { _, _ -> },
        onClientOutput: (String, ConsoleViewContentType) -> Unit = { _, _ -> }
    ): JasprDaemonProcessHandler {
        // We use a dummy command, but we don't start the process
        val cmd = GeneralCommandLine("echo")
        return JasprDaemonProcessHandler(cmd, onServerStarted, onClientDebugPort, onOutput, onClientOutput)
    }

    fun testParseLine_Unstructured() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })

        handler.parseLine("Hello, World!")
        assertEquals("Hello, World!\n", outputText)
    }

    fun testParseLine_DaemonConnected() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })

        handler.parseLine("[{\"event\":\"daemon.connected\",\"params\":{\"version\":\"0.0.1\",\"pid\":1234}}]")
        assertTrue(outputText.contains("Jaspr daemon connected (v0.0.1)"))
    }

    fun testParseLine_ServerStarted() {
        var vmUri = ""
        val handler = createHandler(onServerStarted = { uri -> vmUri = uri })

        handler.parseLine("[{\"event\":\"server.started\",\"params\":{\"vmServiceUri\":\"http://localhost:1234\"}}]")
        assertEquals("http://localhost:1234", vmUri)
    }

    fun testParseLine_ClientDebugPort() {
        var wsUri = ""
        val handler = createHandler(onClientDebugPort = { uri -> wsUri = uri })

        handler.parseLine("[{\"event\":\"client.debugPort\",\"params\":{\"wsUri\":\"ws://localhost:5678\"}}]")
        assertEquals("ws://localhost:5678", wsUri)
    }

    fun testParseLine_ClientLog() {
        var clientOutput = ""
        val handler = createHandler(onClientOutput = { text, _ -> clientOutput += text })

        handler.parseLine("[{\"event\":\"client.log\",\"params\":{\"log\":\"Browser log message\"}}]")
        assertTrue(clientOutput.contains("  Browser log message"))
    }

    fun testParseLine_UnstructuredAnsiIsDecoded() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })

        // jaspr_cli >= 0.24 prints an ASCII-art banner with raw 24-bit ANSI colors.
        handler.parseLine("   \u001B[38;2;9;56;126m▄\u001B[0m banner")
        assertEquals("   ▄ banner\n", outputText)
    }

    fun testAppTracking_StartedAndStopped() {
        val handler = createHandler()
        assertFalse(handler.hasConnectedApps)

        handler.parseLine("[{\"event\":\"app.started\",\"params\":{\"appId\":\"app-1\"}}]")
        assertTrue(handler.hasConnectedApps)

        handler.parseLine("[{\"event\":\"client.stop\",\"params\":{\"appId\":\"app-1\"}}]")
        assertFalse(handler.hasConnectedApps)
    }

    fun testBuildRestartCommands() {
        val handler = createHandler()
        assertEquals(emptyList<Map<String, Any>>(), handler.buildRestartCommands(fullRestart = false))

        handler.parseLine("[{\"event\":\"app.started\",\"params\":{\"appId\":\"app-1\"}}]")
        val commands = handler.buildRestartCommands(fullRestart = true)

        assertEquals(1, commands.size)
        assertEquals("client.restart", commands[0]["method"])
        assertEquals(mapOf("appId" to "app-1", "fullRestart" to true), commands[0]["params"])
        assertFalse(commands[0]["id"] == handler.buildRestartCommands(fullRestart = true)[0]["id"])
    }

    fun testParseLine_MelosPackagePrefixIsStripped() {
        var vmUri = ""
        val handler = createHandler(onServerStarted = { uri -> vmUri = uri })

        handler.parseLine("[website]: [{\"event\":\"server.started\",\"params\":{\"vmServiceUri\":\"http://localhost:1234\"}}]")
        assertEquals("http://localhost:1234", vmUri)
    }

    fun testParseLine_BracketedTextIsNotTreatedAsMelosPrefix() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })

        handler.parseLine("[INFO]: something")
        assertEquals("[INFO]: something\n", outputText)
    }

    // -------------------------------------------------------------------------
    // Graceful shutdown — destroyProcess must not block the caller
    // -------------------------------------------------------------------------

    /** Polls instead of `waitFor` so the test does not block on the EDT. */
    private fun awaitTermination(handler: JasprDaemonProcessHandler, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return handler.isProcessTerminated
    }

    fun testDestroyProcess_ReturnsImmediatelyAndKillsAfterTimeout() {
        if (SystemInfo.isWindows) return // relies on POSIX `sleep` / `head`
        // `sleep` ignores stdin, like a daemon launched through `melos exec`.
        val handler = JasprDaemonProcessHandler(GeneralCommandLine("sleep", "30"))
        handler.startNotify()

        val start = System.nanoTime()
        handler.destroyProcess()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("destroyProcess blocked for $elapsedMs ms", elapsedMs < 500)

        assertFalse(handler.isProcessTerminated)
        assertTrue(awaitTermination(handler, 5000))
    }

    fun testDestroyProcess_KillsChildrenOfTheLauncher() {
        if (SystemInfo.isWindows) return // relies on POSIX `sh` / `sleep`
        // Like `melos exec` -> jaspr daemon -> server: a launcher that ignores stdin
        // and has a long-running child. Killing only the launcher would orphan it.
        val pidFile = java.io.File.createTempFile("jaspr_child", ".pid").apply { deleteOnExit() }
        val handler = JasprDaemonProcessHandler(
            GeneralCommandLine("sh", "-c", "sleep 300 & echo \$! > '${pidFile.path}'; wait")
        )
        handler.startNotify()
        val deadline = System.currentTimeMillis() + 5000
        while (pidFile.readText().isBlank() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val childPid = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))

        handler.destroyProcess()

        assertTrue(awaitTermination(handler, 5000))
        val childGone = (1..100).any {
            Thread.sleep(50)
            !ProcessHandle.of(childPid).map { p -> p.isAlive }.orElse(false)
        }
        assertTrue("child process $childPid survived destroyProcess()", childGone)
    }

    fun testDestroyProcess_TerminatesEarlyWhenDaemonExits() {
        if (SystemInfo.isWindows) return // relies on POSIX `sleep` / `head`
        // `head -n 1` exits as soon as it reads the daemon.shutdown line.
        val handler = JasprDaemonProcessHandler(GeneralCommandLine("head", "-n", "1"))
        handler.startNotify()

        val start = System.nanoTime()
        handler.destroyProcess()
        assertTrue(awaitTermination(handler, 5000))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("took $elapsedMs ms, expected exit before the kill timeout",
            elapsedMs < JasprDaemonProcessHandler.GRACEFUL_SHUTDOWN_TIMEOUT_MS)
        assertEquals(0, handler.exitCode ?: -1)
    }

    fun testDestroyProcess_SecondCallIsSafe() {
        if (SystemInfo.isWindows) return // relies on POSIX `sleep` / `head`
        val handler = JasprDaemonProcessHandler(GeneralCommandLine("sleep", "30"))
        handler.startNotify()

        handler.destroyProcess()
        handler.destroyProcess()

        assertTrue(awaitTermination(handler, 5000))
        // Calling it again after termination is a no-op.
        handler.destroyProcess()
    }

    fun testFailedRestartResponseIsReported() {
        var outputText = ""
        var outputType: ConsoleViewContentType? = null
        val handler = createHandler(onOutput = { text, type -> outputText += text; outputType = type })
        handler.parseLine("[{\"event\":\"app.started\",\"params\":{\"appId\":\"app-1\"}}]")
        val id = handler.buildRestartCommands(fullRestart = false).single()["id"]

        // jaspr_cli 0.23.x answer to a hot reload request.
        handler.parseLine("[{\"id\":\"$id\",\"result\":{\"code\":1,\"message\":\"hot reload not yet supported by webdev\"}}]")

        assertTrue(outputText.contains("hot reload not yet supported by webdev"))
        assertEquals(ConsoleViewContentType.LOG_WARNING_OUTPUT, outputType)
    }

    fun testSuccessfulAndUnrelatedResponsesAreSilent() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })
        handler.parseLine("[{\"event\":\"app.started\",\"params\":{\"appId\":\"app-1\"}}]")
        val id = handler.buildRestartCommands(fullRestart = true).single()["id"]
        outputText = "" // ignore the "App started" line

        handler.parseLine("[{\"id\":\"$id\",\"result\":{\"code\":0,\"message\":\"Success\"}}]")
        handler.parseLine("[{\"id\":\"999\",\"error\":\"not ours\"}]")

        assertEquals("", outputText)
    }

    fun testSuccessfulHotReloadOn024BetaIsNotReportedAsFailure() {
        var outputText = ""
        val handler = createHandler(onOutput = { text, _ -> outputText += text })
        handler.parseLine("[{\"event\":\"app.started\",\"params\":{\"appId\":\"app-1\"}}]")
        val id = handler.buildRestartCommands(fullRestart = false).single()["id"]
        outputText = ""

        // jaspr_cli 0.24.0-beta.0 answer to a successful hot reload (observed in a real run).
        handler.parseLine("[{\"id\":\"$id\",\"result\":{\"code\":1,\"message\":\"[Response]\"}}]")

        assertEquals("", outputText)
    }
}
