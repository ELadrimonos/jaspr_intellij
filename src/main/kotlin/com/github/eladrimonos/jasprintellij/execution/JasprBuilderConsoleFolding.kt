package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.ConsoleFolding
import com.intellij.openapi.project.Project

/**
 * Folds consecutive `[BUILDER]` lines emitted by `jaspr daemon` into a single
 * collapsible/expandable group in the Run / Debug console.
 *
 * Register in plugin.xml inside <extensions defaultExtensionNs="com.intellij">:
 *   <consoleFolding
 *       implementation="com.github.eladrimonos.jasprintellij.execution.JasprBuilderConsoleFolding"/>
 */
class JasprBuilderConsoleFolding : ConsoleFolding() {

    override fun shouldFoldLine(project: Project, line: String): Boolean =
        line.trimStart().startsWith("[BUILDER]")

    override fun getPlaceholderText(project: Project, lines: List<String>): String {
        val duration = lines.lastOrNull()
            ?.let { Regex("""Built .+ in (\S+);""").find(it)?.groupValues?.get(1) }
            ?.let { " — built in $it" }
            ?: ""
        return "[BUILDER] ··· ${lines.size} lines$duration"
    }

    // false = start a new fold group; don't attach [BUILDER] to the preceding [CLI] line
    override fun shouldBeAttachedToThePreviousLine(): Boolean = false
}