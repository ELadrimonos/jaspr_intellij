package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.ui.ConsoleViewContentType

/**
 * Translates jaspr daemon JSON events into formatted console lines,
 * mapping each event type to the appropriate ConsoleViewContentType.
 */
object JasprConsoleFormatter {

    data class ConsoleLine(val text: String, val type: ConsoleViewContentType)

    // ANSI escape sequence pattern
    private val ansiRegex = Regex("""\033\[[0-9;]*m""")
    // Literal \033 as text (daemon escapes them in JSON strings)
    private val escapedAnsiRegex = Regex("""\\033\[[0-9;]*m""")

    fun format(eventName: String, params: Map<String, Any>): List<ConsoleLine> =
        when (eventName) {
            "daemon.log"     -> formatDaemonLog(params)
            "server.log"     -> formatServerLog(params)
            "server.started" -> formatLifecycle("▶ Server started — VM service: ${params["vmServiceUri"]}")
            "client.start"   -> formatLifecycle("▶ Client starting on ${params["deviceId"]}...")
            "app.started"    -> formatLifecycle("✔ App started (id=${params["appId"]})")
            "client.debugPort" -> formatLifecycle(
                "⚙ Client debug port: ${params["port"]}  ws=${params["wsUri"]}"
            )
            "client.log"     -> {
                val msg = (params["log"] as? String)?.trimEnd() ?: return emptyList()
                listOf(ConsoleLine("  $msg", ConsoleViewContentType.NORMAL_OUTPUT))
            }
            else -> emptyList()
        }

    // -------------------------------------------------------------------------

    private fun formatDaemonLog(params: Map<String, Any>): List<ConsoleLine> {
        val raw = (params["message"] as? String) ?: return emptyList()
        val clean = stripAnsi(raw).trimEnd()
        if (clean.isBlank()) return emptyList()

        return when {
            clean.startsWith("[CLI]") -> listOf(
                ConsoleLine(clean, ConsoleViewContentType.SYSTEM_OUTPUT)
            )
            clean.startsWith("[BUILDER]") -> listOf(
                ConsoleLine(clean, ConsoleViewContentType.LOG_DEBUG_OUTPUT)
            )
            else -> listOf(
                ConsoleLine(clean, ConsoleViewContentType.NORMAL_OUTPUT)
            )
        }
    }

    private fun formatServerLog(params: Map<String, Any>): List<ConsoleLine> {
        val msg = (params["message"] as? String)?.trimEnd() ?: return emptyList()
        val level = params["level"] as? String ?: "info"
        val prefix = when (level) {
            "warning", "warn" -> "[WARN] "
            "error"           -> "[ERROR] "
            "debug"           -> "[DEBUG] "
            else              -> ""
        }
        val type = when (level) {
            "warning", "warn" -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            "error"           -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            "debug"           -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            else              -> ConsoleViewContentType.LOG_INFO_OUTPUT
        }
        return listOf(ConsoleLine("$prefix$msg", type))
    }

    private fun formatLifecycle(message: String) =
        listOf(ConsoleLine(message, ConsoleViewContentType.SYSTEM_OUTPUT))

    private fun stripAnsi(text: String): String =
        text.replace(escapedAnsiRegex, "").replace(ansiRegex, "")
}