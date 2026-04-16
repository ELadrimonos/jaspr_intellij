package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Program runner for Jaspr run configurations.
 *
 * Handles both Run (▶) and Debug (🐛) executors.
 * The debug button in the toolbar is only enabled when a registered runner
 * returns `true` from [canRun] for [DefaultDebugExecutor.EXECUTOR_ID] —
 * this class does exactly that.
 *
 * The actual debugger attachment lives in [JasprRunProfileState]:
 *  - `onServerStarted` → attaches DartVmServiceDebugProcess to the Dart VM
 *  - `onClientDebugPort` → opens Dart DevTools in the browser
 */
class JasprProgramRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "JasprProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        profile is JasprRunConfiguration &&
                executorId in setOf(
            DefaultRunExecutor.EXECUTOR_ID,
            DefaultDebugExecutor.EXECUTOR_ID,
        )

    override fun execute(
        environment: ExecutionEnvironment,
        state: RunProfileState,
    ): Promise<RunContentDescriptor?> {
        val result = state.execute(environment.executor, this)
            ?: return resolvedPromise(null)

        return resolvedPromise(
            RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
        )
    }
}