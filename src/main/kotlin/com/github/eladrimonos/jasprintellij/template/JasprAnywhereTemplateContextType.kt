package com.github.eladrimonos.jasprintellij.template

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.ide.template.DartTemplateContextType
import com.jetbrains.lang.dart.psi.DartFile

class JasprAnywhereTemplateContextType :
    DartTemplateContextType("Jaspr") {

    val id: String
        get() = "JASPR_ANYWHERE"

    override fun isInContext(element: PsiElement): Boolean {
        return PsiTreeUtil.getParentOfType(
            element,
            DartFile::class.java,
        ) != null
    }
}