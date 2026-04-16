package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.ui.ConsoleViewContentType
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
}
