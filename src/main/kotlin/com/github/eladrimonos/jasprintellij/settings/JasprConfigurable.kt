package com.github.eladrimonos.jasprintellij.settings

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings | Languages & Frameworks | Jaspr. */
class JasprConfigurable : BoundConfigurable(JasprBundle.message("jaspr.settings.display.name")) {

    private val settings = JasprSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group(JasprBundle.message("jaspr.settings.editor.group")) {
            row {
                checkBox(JasprBundle.message("jaspr.settings.ui.guides"))
                    .bindSelected(settings::showUiGuides)
                    .comment(JasprBundle.message("jaspr.settings.ui.guides.comment"))
            }
        }
    }

    override fun apply() {
        super.apply()
        // Rerun the UI guides pass in open editors so the change shows right away.
        ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart() }
    }
}
