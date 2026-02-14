package com.github.eladrimonos.jasprintellij.template.file

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

class NewJasprFileAction :
   CreateFileFromTemplateAction(
        "Jaspr Component",
        "Create a new Jaspr file from component template",
        JasprIcons.FileIcon
    ) {

    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder
    ) {
        builder
            .setTitle("New Jaspr File")
            .addKind("Stateless", null, "StatelessComponent.dart")
            .addKind("Stateful", null, "StatefulComponent.dart")
            .addKind("Inherited", null, "InheritedComponent.dart")
    }



    override fun getActionName(
        directory: PsiDirectory,
        newName: String,
        templateName: String
    ): String = "Create $newName"
}