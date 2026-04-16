package com.github.eladrimonos.jasprintellij.execution

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprBuilderConsoleFoldingTest : BasePlatformTestCase() {

    private val folding = JasprBuilderConsoleFolding()

    fun testShouldFoldLine() {
        assertTrue(folding.shouldFoldLine(project, "[BUILDER] Compiling..."))
        assertTrue(folding.shouldFoldLine(project, "  [BUILDER] Compiling..."))
        assertFalse(folding.shouldFoldLine(project, "[CLI] Starting..."))
        assertFalse(folding.shouldFoldLine(project, "Regular output"))
    }

    fun testGetPlaceholderText_WithDuration() {
        val lines = listOf(
            "[BUILDER] Compiling...",
            "[BUILDER] Built lib/main.server.dart in 1.2s;"
        )
        val placeholder = folding.getPlaceholderText(project, lines)
        assertEquals("[BUILDER] ··· 2 lines — built in 1.2s", placeholder)
    }

    fun testGetPlaceholderText_WithoutDuration() {
        val lines = listOf(
            "[BUILDER] Compiling...",
            "[BUILDER] Still working..."
        )
        val placeholder = folding.getPlaceholderText(project, lines)
        assertEquals("[BUILDER] ··· 2 lines", placeholder)
    }

    fun testShouldBeAttachedToThePreviousLine() {
        assertFalse(folding.shouldBeAttachedToThePreviousLine())
    }
}
