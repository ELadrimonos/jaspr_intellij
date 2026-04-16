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

    fun testEmptyOutput() {
        val result = JasprConsoleFormatter.format("unknown.event", emptyMap())
        assertEquals(0, result.size)
    }
}
