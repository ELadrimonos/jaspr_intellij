package com.github.eladrimonos.jasprintellij.inspection

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.services.JasprPackageVersion
import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.psi.DartArgumentList
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartElement
import com.jetbrains.lang.dart.psi.DartListLiteralExpression
import com.jetbrains.lang.dart.psi.DartReferenceExpression
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import com.jetbrains.lang.dart.psi.DartVisitor

/**
 * Jaspr 0.24 renders nested `css()` rules as native CSS nesting, which has no selector
 * concatenation: `css('.button', [css('&-active')])` no longer produces `.button-active`.
 * Reports such child selectors and offers to move the rule out next to its parent.
 */
class JasprNestedSelectorConcatenationInspection : LocalInspectionTool() {

    companion object {
        const val NATIVE_NESTING_VERSION = "0.24.0"

        // `&` directly followed by a name character appends to the parent selector.
        private val CONCATENATION_REGEX = Regex("""&[\w-]""")

        private val tooling = JasprTooling()
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!tooling.isVersionAtLeast(JasprPackageVersion.of(holder.file), NATIVE_NESTING_VERSION)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : DartVisitor() {
            override fun visitCallExpression(call: DartCallExpression) {
                super.visitCallExpression(call)
                if (!isCssCall(call)) return
                val literal = selectorLiteral(call) ?: return
                val selector = literalValue(literal) ?: return
                if (!CONCATENATION_REGEX.containsMatchIn(selector)) return
                if (!isNestedInCssRule(call)) return

                val fix = MoveOutOfParentFix.newSelector(literal)?.let(::MoveOutOfParentFix)
                holder.registerProblem(
                    literal,
                    JasprBundle.message("jaspr.inspection.nested.selector.concatenation.problem"),
                    *listOfNotNull(fix).toTypedArray(),
                )
            }
        }
    }

    private class MoveOutOfParentFix(private val newSelector: String) : LocalQuickFix {
        override fun getName(): String =
            JasprBundle.message("jaspr.inspection.nested.selector.concatenation.fix", newSelector)

        override fun getFamilyName(): String =
            JasprBundle.message("jaspr.inspection.nested.selector.concatenation.fix.family")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? DartStringLiteralExpression ?: return
            val selector = newSelector(literal) ?: return
            val child = ruleElement(literal.parentCall() ?: return) ?: return
            val parentList = child.parent as? DartListLiteralExpression ?: return
            val parent = ruleElement(parentList.parentCall() ?: return) ?: return

            val file = literal.containingFile
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

            val quote = literal.text.first { it == '\'' || it == '"' }
            val childStart = child.textRange.startOffset
            val newChildText = child.text.replaceRange(
                literal.textRange.startOffset - childStart,
                literal.textRange.endOffset - childStart,
                "$quote$selector$quote",
            )

            // Insert after the parent first: it lies after the child, so the child's offsets stay valid.
            val insertAt = parent.textRange.endOffset
            document.insertString(insertAt, ",\n$newChildText")
            val edited = document.createRangeMarker(parent.textRange.startOffset, insertAt + 2 + newChildText.length)
            val removal = if (parentList.elementList.size == 1) {
                // The child was the only nested rule: drop the whole `, [...]` argument.
                val comma = PsiTreeUtil.skipSiblingsBackward(parentList, PsiWhiteSpace::class.java)
                val start = if (comma?.text == ",") comma.textRange.startOffset else parentList.textRange.startOffset
                start until parentList.textRange.endOffset
            } else {
                removalRange(child)
            }
            document.deleteString(removal.first, removal.last + 1)

            PsiDocumentManager.getInstance(project).commitDocument(document)
            CodeStyleManager.getInstance(project).reformatText(file, edited.startOffset, edited.endOffset)
            edited.dispose()
        }

        /** The child element plus its separating comma and the whitespace before it. */
        private fun removalRange(child: DartElement): IntRange {
            val previous = PsiTreeUtil.skipSiblingsBackward(child, PsiWhiteSpace::class.java)
            val next = PsiTreeUtil.skipSiblingsForward(child, PsiWhiteSpace::class.java)
            if (next?.text == ",") {
                val start = previous?.textRange?.endOffset ?: child.textRange.startOffset
                return start until next.textRange.endOffset
            }
            val start = if (previous?.text == ",") previous.textRange.startOffset else child.textRange.startOffset
            return start until child.textRange.endOffset
        }

        companion object {
            /**
             * The selector the rule gets once moved next to its parent, or null when no
             * fix applies: the parent must be a plain `css('<selector>', [...])` inside a
             * list, and a selector list parent would need every combination spelled out.
             */
            fun newSelector(literal: DartStringLiteralExpression): String? {
                val childSelector = literalValue(literal) ?: return null
                val child = ruleElement(literal.parentCall() ?: return null) ?: return null
                val parentList = child.parent as? DartListLiteralExpression ?: return null
                val parentCall = parentList.parentCall() ?: return null
                if (!isCssCall(parentCall)) return null
                if (parentCall.arguments?.argumentList?.expressionList?.getOrNull(1) != parentList) return null
                if (ruleElement(parentCall) == null) return null
                val parentSelector = selectorLiteral(parentCall)?.let(::literalValue) ?: return null
                if (',' in parentSelector || ',' in childSelector) return null
                return childSelector.replace("&", parentSelector)
            }
        }
    }
}

private fun isCssCall(call: DartCallExpression): Boolean = call.expression?.text == "css"

/** `css(...)` itself or one of its at-rule variants, such as `css.media(...)`. */
private fun isCssRuleCall(call: DartCallExpression): Boolean =
    call.expression?.text?.let { it == "css" || it.startsWith("css.") } == true

private fun selectorLiteral(call: DartCallExpression): DartStringLiteralExpression? =
    call.arguments?.argumentList?.expressionList?.firstOrNull() as? DartStringLiteralExpression

/** The call whose positional argument list directly contains [this]. */
private fun PsiElement.parentCall(): DartCallExpression? =
    (parent as? DartArgumentList)?.parent?.parent as? DartCallExpression

private fun isNestedInCssRule(call: DartCallExpression): Boolean {
    var current: PsiElement = call
    while (true) {
        val list = PsiTreeUtil.getParentOfType(current, DartListLiteralExpression::class.java, true) ?: return false
        val owner = list.parentCall()
        if (owner != null && isCssRuleCall(owner)) return true
        current = list
    }
}

/** The list element holding the rule built by [call], including chained calls like `.styles(...)`. */
private fun ruleElement(call: DartCallExpression): DartElement? {
    var expression: PsiElement = call
    while (expression.parent is DartReferenceExpression || expression.parent is DartCallExpression) {
        expression = expression.parent
    }
    return expression.parent as? DartElement
}

/** The value of a plain, single-line string literal without interpolation. */
private fun literalValue(literal: DartStringLiteralExpression): String? {
    if (literal.shortTemplateEntryList.isNotEmpty() || literal.longTemplateEntryList.isNotEmpty()) return null
    val text = literal.text.removePrefix("r")
    if (text.length < 2 || text.startsWith("'''") || text.startsWith("\"\"\"")) return null
    val quote = text.first()
    if ((quote != '\'' && quote != '"') || text.last() != quote) return null
    return text.substring(1, text.length - 1)
}
