package com.github.eladrimonos.jasprintellij.editor.guides

import com.intellij.codeInsight.daemon.impl.IndentsPassFilter
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.psi.PsiDocumentManager

/**
 * Hides the platform indent guides drawn in the same column as a Jaspr UI guide, so the
 * two don't stack on top of each other. Reads the guides from the PSI rather than from
 * the UI guides pass, which may not have run yet when the indents pass does.
 */
class JasprIndentsPassFilter : IndentsPassFilter {

    override fun shouldRunIndentsPass(editor: Editor): Boolean = true

    override fun shouldShowIndentGuide(editor: Editor, descriptor: IndentGuideDescriptor): Boolean {
        val project = editor.project ?: return true
        val documents = PsiDocumentManager.getInstance(project)
        if (!documents.isCommitted(editor.document)) return true
        val file = documents.getPsiFile(editor.document) ?: return true
        return JasprUiGuides.forFile(file).none { overlaps(it, descriptor) }
    }

    internal fun overlaps(guide: JasprUiGuide, descriptor: IndentGuideDescriptor): Boolean =
        descriptor.indentLevel == guide.indent &&
            descriptor.startLine <= guide.lastChildLine &&
            descriptor.endLine > guide.line
}
