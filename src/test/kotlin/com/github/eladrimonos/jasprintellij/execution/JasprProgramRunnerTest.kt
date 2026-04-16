package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprConfigurationFactory
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprProgramRunner
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfiguration
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprProgramRunnerTest : BasePlatformTestCase() {

    fun testCanRun() {
        val runner = JasprProgramRunner()
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)
        val config = JasprRunConfiguration(project, factory, "Test")

        // Should run with default run and debug executors
        assertTrue(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, config))
        assertTrue(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, config))

        // Should not run other types of configurations
        // (Hard to test without another configuration type available, but let's trust the type check)
    }
}
