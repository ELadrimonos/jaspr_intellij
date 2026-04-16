package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.lang.dart.ide.runner.server.vmService.DartVmServiceDebugProcess
import com.jetbrains.lang.dart.util.DartUrlResolver
import com.intellij.openapi.vfs.VirtualFile

class JasprVmServiceDebugProcess(
    session: XDebugSession,
    executionResult: ExecutionResult?, // Importante para la consola
    private val vmServiceWsUri: String,
    dartUrlResolver: DartUrlResolver,
    timeout: Int,
    currentWorkingDirectory: VirtualFile?,
) : DartVmServiceDebugProcess(
    session,
    executionResult, // Pasamos el resultado
    dartUrlResolver,
    null,
    DebugType.REMOTE,
    timeout,
    currentWorkingDirectory,
) {
    @Throws(ExecutionException::class)
    override fun start() {
        scheduleConnect(vmServiceWsUri)
    }
}