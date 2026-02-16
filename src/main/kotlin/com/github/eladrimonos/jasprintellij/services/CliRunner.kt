package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput

interface CliRunner {
    fun run(commandLine: GeneralCommandLine): ProcessOutput
}