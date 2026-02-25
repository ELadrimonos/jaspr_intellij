package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.intellij.execution.configurations.ConfigurationTypeBase

class JasprRunConfigurationType : ConfigurationTypeBase(
    "JasprRunConfiguration",
    "Jaspr",
    "Jaspr Run Configuration",
    JasprIcons.FileIcon
) {
    init {
        addFactory(JasprConfigurationFactory(this))
    }
}