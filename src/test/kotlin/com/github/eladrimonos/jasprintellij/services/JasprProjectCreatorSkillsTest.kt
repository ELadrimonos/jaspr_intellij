package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class JasprProjectCreatorSkillsTest : BasePlatformTestCase() {

    /** Answers `jaspr --version` with [cliVersion] and records every `jaspr create` call. */
    private class FakeCliRunner(private val cliVersion: String) : CliRunner {
        val createCalls = mutableListOf<List<String>>()

        override fun run(commandLine: GeneralCommandLine): ProcessOutput {
            val params = commandLine.parametersList.list
            if ("create" in params) createCalls += params
            return ProcessOutput(if ("--version" in params) cliVersion else "", "", 0, false, false)
        }
    }

    private fun createWith(cliVersion: String, options: JasprProjectCreator.Options): List<String> {
        val runner = FakeCliRunner(cliVersion)
        val projectDir = Files.createTempDirectory("jaspr_skills_test").toFile()
        try {
            JasprProjectCreator(cliRunner = runner, environmentProvider = { emptyMap() })
                .create(projectDir, "/fake/sdk", options)
        } finally {
            projectDir.deleteRecursively()
        }
        return runner.createCalls.single()
    }

    fun testCli024_SkillsDisabledByDefault() {
        val args = createWith("0.24.0-beta.0", JasprProjectCreator.Options())
        assertTrue(args.contains("--no-skills"))
        assertFalse(args.contains("--skills"))
    }

    fun testCli024_SkillsEnabled() {
        val args = createWith("0.24.0", JasprProjectCreator.Options(installSkills = true))
        assertTrue(args.contains("--skills"))
        assertFalse(args.contains("--no-skills"))
    }

    fun testLegacyCli_NoSkillsFlag() {
        val args = createWith("0.23.4", JasprProjectCreator.Options(installSkills = true))
        assertFalse(args.contains("--skills"))
        assertFalse(args.contains("--no-skills"))
    }
}
