package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprConsoleFormatterTest : BasePlatformTestCase() {

    fun testFormatDaemonLog_CliPrefix() {
        val params = mapOf("message" to "[CLI] Starting jaspr daemon...")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals(1, result.size)
        assertEquals("[CLI] Starting jaspr daemon...", result[0].text)
        assertEquals(ConsoleViewContentType.SYSTEM_OUTPUT, result[0].type)
    }

    fun testFormatDaemonLog_BuilderPrefix() {
        val params = mapOf("message" to "[BUILDER] Building...")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals(1, result.size)
        assertEquals("[BUILDER] Building...", result[0].text)
        assertEquals(ConsoleViewContentType.LOG_DEBUG_OUTPUT, result[0].type)
    }

    fun testFormatDaemonLog_OtherPrefix() {
        val params = mapOf("message" to "Generic message")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals(1, result.size)
        assertEquals("Generic message", result[0].text)
        assertEquals(ConsoleViewContentType.NORMAL_OUTPUT, result[0].type)
    }

    fun testFormatServerLog_Warning() {
        val params = mapOf("message" to "Something is wrong", "level" to "warning")
        val result = JasprConsoleFormatter.format("server.log", params)

        assertEquals(1, result.size)
        assertEquals("[WARN] Something is wrong", result[0].text)
        assertEquals(ConsoleViewContentType.LOG_WARNING_OUTPUT, result[0].type)
    }

    fun testFormatServerLog_Error() {
        val params = mapOf("message" to "Critical failure", "level" to "error")
        val result = JasprConsoleFormatter.format("server.log", params)

        assertEquals(1, result.size)
        assertEquals("[ERROR] Critical failure", result[0].text)
        assertEquals(ConsoleViewContentType.LOG_ERROR_OUTPUT, result[0].type)
    }

    fun testFormatLifecycle_ServerStarted() {
        val params = mapOf("vmServiceUri" to "http://localhost:8181")
        val result = JasprConsoleFormatter.format("server.started", params)

        assertEquals(1, result.size)
        assertEquals("▶ Server started — VM service: http://localhost:8181", result[0].text)
        assertEquals(ConsoleViewContentType.SYSTEM_OUTPUT, result[0].type)
    }

    fun testStripAnsi() {
        // We need to test stripAnsi through format if it's private, but it's used in formatDaemonLog
        val params = mapOf("message" to "\\033[32mColored\\033[0m text")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals(1, result.size)
        assertEquals("Colored text", result[0].text)
    }

    // ── jaspr_cli >= 0.24 badge tags (captured from a real 0.24.0-beta.0 daemon) ──

    fun testFormatDaemonLog_024BuilderBadge() {
        val params = mapOf("message" to
            "\\033[45m\\033[97m\\033[1m BUILDER \\033[22m\\033[0m\\033[0m Starting web compilers...")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals("[BUILDER] Starting web compilers...", result.single().text)
        assertEquals(ConsoleViewContentType.LOG_DEBUG_OUTPUT, result.single().type)
    }

    fun testFormatDaemonLog_024LogBadgeMapsToCli() {
        val params = mapOf("message" to "\\033[100m\\033[97m\\033[1m LOG     \\033[22m\\033[0m\\033[0m Running...")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals("[CLI] Running...", result.single().text)
        assertEquals(ConsoleViewContentType.SYSTEM_OUTPUT, result.single().type)
    }

    fun testFormatDaemonLog_024ServerError() {
        val params = mapOf("message" to
            "\\033[43m\\033[30m\\033[1m SERVER  \\033[22m\\033[0m\\033[0m \\033[91m\\033[1m[ERROR] \\033[22m\\033[0m" +
            "\\033[91m\\033[1mCould not start the VM service\\033[22m\\033[0m")
        val result = JasprConsoleFormatter.format("daemon.log", params)

        assertEquals("[SERVER] [ERROR] Could not start the VM service", result.single().text)
        assertEquals(ConsoleViewContentType.LOG_ERROR_OUTPUT, result.single().type)
    }

    fun testFormatDaemonLog_UntaggedWarning() {
        val result = JasprConsoleFormatter.format("daemon.log", mapOf("message" to "[WARNING] Port in use"))
        assertEquals(ConsoleViewContentType.LOG_WARNING_OUTPUT, result.single().type)
    }

    fun testFormatDaemonLog_PlainMessageStartingWithTagWordIsUntouched() {
        val result = JasprConsoleFormatter.format("daemon.log", mapOf("message" to "Server started"))
        assertEquals("Server started", result.single().text)
    }

    fun testFormatAppProgressAndLog() {
        val progress = JasprConsoleFormatter.format(
            "app.progress", mapOf("appId" to "a", "message" to "Performing hot reload...")
        )
        assertEquals("⟳ Performing hot reload...", progress.single().text)

        val finished = JasprConsoleFormatter.format("app.progress", mapOf("appId" to "a", "finished" to true))
        assertEquals(0, finished.size)

        val log = JasprConsoleFormatter.format("app.log", mapOf("appId" to "a", "log" to "Reloaded application in 42ms"))
        assertEquals("✔ Reloaded application in 42ms", log.single().text)
    }

    fun testEmptyOutput() {
        val result = JasprConsoleFormatter.format("unknown.event", emptyMap())
        assertEquals(0, result.size)
    }
}
