package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider

class JasprCodeVisionGroupSettingProvider : CodeVisionGroupSettingProvider {
    override val groupId: String = "jaspr.component.scopes"

    override val groupName: String
        get() = JasprBundle.message("jaspr.component.scopes.group.name")

    override val description: String
        get() = JasprBundle.message("jaspr.component.scopes.group.description")
}
