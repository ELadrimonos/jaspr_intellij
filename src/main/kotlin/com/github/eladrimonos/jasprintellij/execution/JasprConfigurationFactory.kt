package com.github.eladrimonos.jasprintellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

/**
 * Factory for creating Jaspr run configurations.
 */
class JasprConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = "JasprRunConfiguration"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        JasprRunConfiguration(project, this, "Jaspr")

    override fun getOptionsClass(): Class<JasprRunConfigurationOptions> =
        JasprRunConfigurationOptions::class.java
}