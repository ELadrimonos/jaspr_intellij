package com.github.eladrimonos.jasprintellij.execution

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets

/**
 * Process handler for the `jaspr daemon` protocol.
 *
 * Mirrors the VS Code JasprServeDaemon: launches `jaspr daemon`, parses
 * the JSON event stream, exposes callbacks for `server.started` and
 * `client.debugPort`, and sends `daemon.shutdown` before killing the process
 * so that child processes (Chrome, web dev server) are cleaned up properly.
 *
 * Output routing:
 *  - [onOutput]       — daemon-level and server-side events  → server console
 *  - [onClientOutput] — client-side events (client.*)        → client console
 */
class JasprDaemonProcessHandler(
    commandLine: GeneralCommandLine,
    val onServerStarted: (vmServiceUri: String) -> Unit = {},
    val onClientDebugPort: (wsUri: String) -> Unit = {},
    val onOutput: (text: String, type: ConsoleViewContentType) -> Unit = { _, _ -> },
    val onClientOutput: (text: String, type: ConsoleViewContentType) -> Unit = { _, _ -> },
) : KillableColoredProcessHandler(commandLine) {

    private val logger = Logger.getInstance(JasprDaemonProcessHandler::class.java)
    private val gson = Gson()
    private val outputBuffer = StringBuilder()

    init {
        addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
            override fun processTerminated(event: ProcessEvent) {
                logger.info("jaspr daemon terminated (exit=${event.exitCode})")
            }
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(outputBuffer) {
                    outputBuffer.append(event.text)
                    val content = outputBuffer.toString()
                    val lastNewline = content.lastIndexOf('\n')
                    if (lastNewline < 0) return
                    val lines = content.substring(0, lastNewline)
                    outputBuffer.delete(0, lastNewline + 1)
                    lines.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { parseLine(it) }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Daemon protocol — matches VS Code's handleData / handleEvent
    // -------------------------------------------------------------------------

    internal fun parseLine(line: String) {
        if (line.startsWith("[{") && line.endsWith("}]")) {
            try {
                @Suppress("UNCHECKED_CAST")
                val events = gson.fromJson(line, List::class.java) as List<Map<String, Any>>
                events.forEach { handleEvent(it) }
                return
            } catch (_: Exception) {}
        }
        // Unstructured output is treated as server/daemon-level output
        onOutput("$line\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun handleEvent(event: Map<String, Any>) {
        val eventName = event["event"] as? String ?: return
        val params = event["params"] as? Map<String, Any> ?: emptyMap()

        when (eventName) {
            "daemon.connected" -> {
                logger.info("jaspr daemon connected: version=${params["version"]}, pid=${params["pid"]}")
                onOutput("⚡ Jaspr daemon connected (v${params["version"]})\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            "server.started" -> {
                val uri = params["vmServiceUri"] as? String ?: return
                onServerStarted(uri)
            }
            "client.debugPort" -> {
                val uri = params["wsUri"] as? String ?: return
                onClientDebugPort(uri)
            }
            else -> {
                // Route client.* events to the client console; everything else to server.
                val isClientEvent = eventName.startsWith("client.")
                JasprConsoleFormatter.format(eventName, params).forEach { line ->
                    if (isClientEvent) onClientOutput(line.text + "\n", line.type)
                    else               onOutput(line.text + "\n", line.type)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Graceful shutdown — send daemon.shutdown first, like VS Code does
    // -------------------------------------------------------------------------

    /**
     * Sends `daemon.shutdown` over stdin so that jaspr can clean up Chrome
     * and child processes before we kill the JVM-side process handle.
     */
    fun sendShutdown() {
        sendCommand(mapOf("method" to "daemon.shutdown", "id" to "0"))
    }

    private fun sendCommand(command: Map<String, Any>) {
        if (isProcessTerminated) return
        val json = gson.toJson(listOf(command))
        logger.debug("→ daemon: $json")
        try {
            processInput.write(json.toByteArray(StandardCharsets.UTF_8))
            processInput.write("\n".toByteArray(StandardCharsets.UTF_8))
            processInput.flush()
        } catch (e: Exception) {
            logger.warn("Failed to write to daemon stdin: ${e.message}")
        }
    }

    override fun destroyProcess() {
        // Send graceful shutdown first, then let the base class kill the handle.
        // This gives jaspr ~2 s to clean up Chrome and child processes.
        sendShutdown()
        // Small delay so jaspr can react before we SIGKILL
        Thread.sleep(1500)
        super.destroyProcess()
    }
}