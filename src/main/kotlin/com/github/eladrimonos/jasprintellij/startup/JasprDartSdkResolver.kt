package com.github.eladrimonos.jasprintellij.startup

import com.intellij.openapi.project.Project
import com.jetbrains.lang.dart.sdk.DartSdk

object JasprDartSdkResolver {
    fun getConfiguredDartSdkHomePath(project: Project): String? {
        return DartSdk.getDartSdk(project)?.homePath?.takeIf { it.isNotBlank() }
    }
}