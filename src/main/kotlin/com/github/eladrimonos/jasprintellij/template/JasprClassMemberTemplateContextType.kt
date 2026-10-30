package com.github.eladrimonos.jasprintellij.template

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.ide.template.DartTemplateContextType
import com.jetbrains.lang.dart.psi.DartClassDefinition
import com.jetbrains.lang.dart.psi.DartFunctionBody

/**
 * Directly inside a class body (where members are declared), but not inside
 * a method or function body.
 */
class JasprClassMemberTemplateContextType :
    DartTemplateContextType("Jaspr") {

    val id: String
        get() = "JASPR_CLASS_MEMBER"

    public override fun isInContext(element: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java) != null) return false

        // The nearest enclosing class-or-body decides: a class means we're declaring members.
        return PsiTreeUtil.getParentOfType(
            element,
            DartClassDefinition::class.java,
            DartFunctionBody::class.java,
        ) is DartClassDefinition
    }
}
