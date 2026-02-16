package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil

object DefaultCliRunner : CliRunner {
    override fun run(commandLine: GeneralCommandLine): ProcessOutput =
        ExecUtil.execAndGetOutput(commandLine)
}