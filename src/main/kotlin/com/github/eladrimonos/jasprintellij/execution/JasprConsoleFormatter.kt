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
    // jaspr_cli >= 0.24 renders tags as a padded badge (" BUILDER ", " SERVER  ")
    // instead of "[BUILDER] "; once ANSI is stripped only the bare word remains.
    private val legacyTagRegex = Regex("""^\[(CLI|BUILDER|SERVER|FLUTTER|CLIENT|STYLES)] """)
    private val badgeTagRegex = Regex("""^\s*(LOG|BUILDER|SERVER|FLUTTER|CLIENT|STYLES)\s+""")

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
            "app.progress"   -> (params["message"] as? String)
                ?.takeIf { params["finished"] != true }
                ?.let { formatLifecycle("⟳ ${it.trimEnd()}") }
                ?: emptyList()
            "app.log"        -> (params["log"] as? String)
                ?.let { formatLifecycle("✔ ${it.trimEnd()}") }
                ?: emptyList()
            "client.log"     -> {
                val msg = (params["log"] as? String)?.trimEnd() ?: return emptyList()
                listOf(ConsoleLine("  $msg", ConsoleViewContentType.NORMAL_OUTPUT))
            }
            else -> emptyList()
        }

    // -------------------------------------------------------------------------

    private fun formatDaemonLog(params: Map<String, Any>): List<ConsoleLine> {
        val raw = (params["message"] as? String) ?: return emptyList()
        val clean = normalizeTag(stripAnsi(raw).trimEnd())
        if (clean.isBlank()) return emptyList()
        val body = clean.replaceFirst(legacyTagRegex, "")

        return when {
            body.startsWith("[ERROR]") || body.startsWith("[CRITICAL]") -> listOf(
                ConsoleLine(clean, ConsoleViewContentType.LOG_ERROR_OUTPUT)
            )
            body.startsWith("[WARNING]") -> listOf(
                ConsoleLine(clean, ConsoleViewContentType.LOG_WARNING_OUTPUT)
            )
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

    /** Rewrites a 0.24-style badge tag (" BUILDER  msg") to the legacy "[BUILDER] msg" form. */
    private fun normalizeTag(text: String): String {
        val match = badgeTagRegex.find(text) ?: return text
        val name = match.groupValues[1].let { if (it == "LOG") "CLI" else it }
        return "[$name] " + text.substring(match.range.last + 1)
    }

    private fun stripAnsi(text: String): String =
        text.replace(escapedAnsiRegex, "").replace(ansiRegex, "")
}