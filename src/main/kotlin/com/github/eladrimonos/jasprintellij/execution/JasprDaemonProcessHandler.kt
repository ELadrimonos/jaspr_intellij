package com.github.eladrimonos.jasprintellij.execution

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val nextRequestId = AtomicInteger(0)
    private val ansiDecoder = AnsiEscapeDecoder()
    private val melosLinePrefix = Regex("""^\[[^\]\s]+]: (?=\[\{)""")

    /** Ids of the client apps currently connected (`app.started` → `client.stop`). */
    private val connectedAppIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val hasConnectedApps: Boolean get() = connectedAppIds.isNotEmpty()

    /** Ids of `client.restart` requests whose response hasn't arrived yet. */
    private val pendingRestartIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Set once the first [destroyProcess] call schedules the graceful stop. */
    private val shutdownRequested = AtomicBoolean(false)

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
                        // Only trim the end: leading spaces matter for unstructured
                        // output such as the jaspr_cli >= 0.24 ASCII-art banner.
                        .map { it.trimEnd() }
                        .filter { it.isNotBlank() }
                        .forEach { parseLine(it) }
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Daemon protocol — matches VS Code's handleData / handleEvent
    // -------------------------------------------------------------------------

    internal fun parseLine(line: String) {
        // `melos exec` prefixes each line with "[package]: " when it runs in more
        // than one package; strip it so the JSON event is still recognised.
        val trimmed = line.trim().replaceFirst(melosLinePrefix, "")
        if (trimmed.startsWith("[{") && trimmed.endsWith("}]")) {
            try {
                @Suppress("UNCHECKED_CAST")
                val events = gson.fromJson(trimmed, List::class.java) as List<Map<String, Any>>
                events.forEach { handleEvent(it) }
                return
            } catch (_: Exception) {}
        }
        // Unstructured output is treated as server/daemon-level output. It may carry
        // raw ANSI colors (e.g. the jaspr_cli >= 0.24 banner), so decode them.
        ansiDecoder.escapeText("$line\n", ProcessOutputTypes.STDOUT) { text, attributes ->
            onOutput(text, ConsoleViewContentType.getConsoleViewType(attributes))
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun handleEvent(event: Map<String, Any>) {
        val eventName = event["event"] as? String ?: return handleResponse(event)
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
            "app.started", "client.stop" -> {
                (params["appId"] as? String)?.let {
                    if (eventName == "app.started") connectedAppIds.add(it) else connectedAppIds.remove(it)
                }
                // Let toolbars re-evaluate the hot reload buttons now, not on the next user input.
                ActivityTracker.getInstance().inc()
                formatToConsole(eventName, params)
            }
            else -> formatToConsole(eventName, params)
        }
    }

    private fun formatToConsole(eventName: String, params: Map<String, Any>) {
        // Route client.* events to the client console; everything else to server.
        val isClientEvent = eventName.startsWith("client.")
        JasprConsoleFormatter.format(eventName, params).forEach { line ->
            if (isClientEvent) onClientOutput(line.text + "\n", line.type)
            else               onOutput(line.text + "\n", line.type)
        }
    }

    // -------------------------------------------------------------------------
    // Hot reload / hot restart — `client.restart` (client app only; server and
    // styles are reloaded by `--auto` after each rebuild)
    // -------------------------------------------------------------------------

    /** Sends `client.restart` to every connected app. [fullRestart] = hot restart. */
    fun restartClients(fullRestart: Boolean) {
        buildRestartCommands(fullRestart).forEach { sendCommand(it) }
    }

    internal fun buildRestartCommands(fullRestart: Boolean): List<Map<String, Any>> =
        connectedAppIds.map { appId ->
            val id = nextRequestId.incrementAndGet().toString()
            pendingRestartIds += id
            mapOf(
                "method" to "client.restart",
                "id" to id,
                "params" to mapOf("appId" to appId, "fullRestart" to fullRestart),
            )
        }

    /**
     * Handles a response (`{"id": …, "result" | "error": …}`) to a command we sent and
     * surfaces `client.restart` failures, e.g. jaspr_cli < 0.24 answering hot reload
     * with `code: 1, "hot reload not yet supported by webdev"`.
     *
     * jaspr_cli 0.24.0-beta.0 also returns `code: 1` for a *successful* hot reload
     * (it maps any VM response whose type isn't "Success" to 1), with the bare VM
     * response `toString()` — "[Response]" — as message. Those aren't reported.
     */
    private fun handleResponse(response: Map<String, Any>) {
        val id = response["id"]?.toString() ?: return
        if (!pendingRestartIds.remove(id)) return

        val result = response["result"] as? Map<*, *>
        val failure = response["error"]?.toString()
            ?: result?.takeIf { (it["code"] as? Number)?.toInt() != 0 }
                ?.get("message")?.toString()
                ?.takeUnless { VM_RESPONSE_TO_STRING.matches(it) }
        if (failure != null) {
            onOutput("⚠ Hot reload/restart failed: $failure\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
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
        sendCommand(mapOf("method" to "daemon.shutdown", "id" to nextRequestId.incrementAndGet().toString()))
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

    /**
     * Stops the daemon without blocking the caller (the Stop button calls this
     * on the EDT).
     *
     * Sends `daemon.shutdown` first, then waits on a pooled thread for up to
     * [GRACEFUL_SHUTDOWN_TIMEOUT_MS] for jaspr to exit on its own. If it is still
     * alive after that, the base class kills it. The fallback also covers
     * `melos exec`, which does not forward stdin, so the shutdown never arrives.
     *
     * Only the first call does anything; later calls are ignored.
     */
    override fun destroyProcess() {
        if (isProcessTerminated) return
        if (!shutdownRequested.compareAndSet(false, true)) return

        sendShutdown()
        AppExecutorUtil.getAppExecutorService().execute {
            if (waitFor(GRACEFUL_SHUTDOWN_TIMEOUT_MS)) return@execute
            if (isProcessTerminated || isProcessTerminating) return@execute
            logger.info("jaspr daemon did not exit after daemon.shutdown; killing it")
            killProcessHandle()
        }
    }

    /**
     * Kills the daemon and every process it spawned. The process we started is often
     * only a launcher (`dart pub global run`, or `melos` when running a Melos script),
     * so killing just that one would orphan the jaspr server — and its DDS, which
     * keeps port 8181 busy for every later run. Descendants are collected before
     * anything is killed, while they're still linked to this process tree.
     */
    private fun killProcessHandle() {
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        super.destroyProcess()
        descendants.filter { it.isAlive }.forEach { it.destroy() }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }, GRACEFUL_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** `toString()` of a package:vm_service response object, e.g. "[Response]" or "[Success]". */
        private val VM_RESPONSE_TO_STRING = Regex("""\[\w*]""")

        /** How long jaspr gets to clean up Chrome and child processes before we kill it. */
        internal const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 2000L
    }
}
