package com.github.eladrimonos.jasprintellij.template.project

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import javax.swing.Icon

class JasprNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "jaspr"
    override val name: String = "Jaspr"
    override val icon: Icon = JasprIcons.FileIcon
    override fun createStep(context: WizardContext): NewProjectWizardStep {
        return RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::GitNewProjectWizardStep)
            .nextStep(::JasprNewProjectWizardStep)
    }
}
