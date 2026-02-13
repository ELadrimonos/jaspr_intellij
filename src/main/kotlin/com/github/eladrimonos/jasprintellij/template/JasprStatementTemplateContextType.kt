package com.github.eladrimonos.jasprintellij.template

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.ide.template.DartTemplateContextType
import com.jetbrains.lang.dart.psi.*
import kotlin.jvm.java

class JasprStatementTemplateContextType :
    DartTemplateContextType("Jaspr") {

    val id: String
        get() = "JASPR_STATEMENT"

    override fun isInContext(element: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java) != null) return false

        return PsiTreeUtil.getParentOfType(
            element,
            DartMethodDeclaration::class.java,
            DartClassDefinition::class.java,
            DartFunctionBody::class.java,
            DartComponent::class.java
        ) != null
    }
}