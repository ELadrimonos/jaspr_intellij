package com.github.eladrimonos.jasprintellij.actions

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * Collects the script name and target package for a new Melos "run jaspr daemon"
 * script, offering a dropdown of the workspace's known member packages when available.
 */
class CreateMelosScriptDialog(
    project: Project,
    knownPackageNames: List<String>,
) : DialogWrapper(project) {

    private val scriptNameField = JBTextField()
    private val packageNameCombo = ComboBox(knownPackageNames.toTypedArray()).apply { isEditable = true }

    val scriptName: String get() = scriptNameField.text.trim()
    val packageName: String get() = (packageNameCombo.editor.item as? String)?.trim().orEmpty()

    init {
        title = JasprBundle.message("jaspr.dialog.melos.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        scriptNameField.emptyText.text = "e.g. serve_website"
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Script name:", scriptNameField)
            .addLabeledComponent("Target package:", packageNameCombo)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = scriptNameField

    override fun doValidate(): ValidationInfo? {
        if (scriptName.isBlank()) return ValidationInfo(JasprBundle.message("jaspr.dialog.melos.script.required"), scriptNameField)
        if (packageName.isBlank()) return ValidationInfo(JasprBundle.message("jaspr.dialog.melos.package.required"), packageNameCombo)
        return null
    }
}
