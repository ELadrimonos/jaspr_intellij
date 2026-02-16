package com.github.eladrimonos.jasprintellij.template

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.ide.template.DartTemplateContextType
import com.jetbrains.lang.dart.psi.DartClassDefinition
import com.jetbrains.lang.dart.psi.DartFile

class JasprTopLevelTemplateContextType :
    DartTemplateContextType("Jaspr") {

    val id: String
        get() = "JASPR_TOPLEVEL"

    override fun isInContext(element: PsiElement): Boolean {
        return PsiTreeUtil.getNonStrictParentOfType(
            element,
            DartClassDefinition::class.java,
            PsiComment::class.java
        ) == null
    }
}