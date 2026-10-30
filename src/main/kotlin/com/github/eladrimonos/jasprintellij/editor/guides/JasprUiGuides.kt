package com.github.eladrimonos.jasprintellij.editor.guides

import com.github.eladrimonos.jasprintellij.services.JasprPackageVersion
import com.github.eladrimonos.jasprintellij.settings.JasprSettings
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.psi.DartArguments
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartComponent
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.psi.DartForElement
import com.jetbrains.lang.dart.psi.DartIfElement
import com.jetbrains.lang.dart.psi.DartListLiteralExpression
import com.jetbrains.lang.dart.psi.DartNewExpression
import com.jetbrains.lang.dart.psi.DartReturnType

/**
 * A component in the editor whose children start on lines below it: the UI guide runs
 * down from its line at [indent] and branches off to each child.
 *
 * @property startOffset start of the component expression, e.g. `div([...])`.
 * @property endOffset end of the component expression.
 * @property line zero-based line of [startOffset].
 * @property indent column of the first non-whitespace character on [line].
 * @property childOffsets start of each child, each the first non-whitespace character of its line.
 * @property lastChildLine line of the last child, where the guide ends.
 */
data class JasprUiGuide(
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val indent: Int,
    val childOffsets: List<Int>,
    val lastChildLine: Int,
)

/**
 * Builds the component tree of a Dart file from its PSI, without the analysis server.
 *
 * A component is a call or constructor invocation with children: list literals passed
 * positionally (`div([...])`, the shape of every Jaspr HTML element) or through a
 * `children:`, `child:` or `body:` named argument. Only code in `build` methods and in
 * functions returning a `Component` counts, so lists passed to other calls such as
 * `Future.wait([...])` get no guides.
 */
object JasprUiGuides {

    private val CHILD_ARGUMENTS = setOf("child", "children", "body")

    /** The guides to show for [file], or none when turned off or outside a Jaspr package. Needs a read action. */
    fun forFile(file: PsiFile): List<JasprUiGuide> {
        if (!JasprSettings.getInstance().showUiGuides) return emptyList()
        if (file !is DartFile || JasprPackageVersion.of(file) == null) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(compute(file), file)
        }
    }

    internal fun compute(file: PsiFile): List<JasprUiGuide> {
        val text = file.viewProvider.contents
        return SyntaxTraverser.psiTraverser(file)
            .filter { it is DartCallExpression || it is DartNewExpression || it is DartForElement || it is DartIfElement }
            .filter { !isStyleRule(it) && isInComponentScope(it) }
            .mapNotNull { node -> guideFor(node, text) }
            .toList()
    }

    private fun guideFor(node: PsiElement, text: CharSequence): JasprUiGuide? {
        val start = node.textRange.startOffset
        val line = StringUtil.offsetToLineNumber(text, start)
        val children = childrenOf(node)
            .map { it.textRange.startOffset }
            .filter { offset ->
                StringUtil.offsetToLineNumber(text, offset) > line && offset == firstCharOfLine(text, offset)
            }
        if (children.isEmpty()) return null
        val lineStart = StringUtil.lineColToOffset(text, line, 0)
        return JasprUiGuide(
            startOffset = start,
            endOffset = node.textRange.endOffset,
            line = line,
            indent = firstCharOfLine(text, start) - lineStart,
            childOffsets = children,
            lastChildLine = StringUtil.offsetToLineNumber(text, children.last()),
        )
    }

    /**
     * The elements the guide branches off to: each element of a children list (including
     * `if`, `for` and spread elements), or the named argument holding a single child. A
     * collection `for` branches off to its body, a collection `if` to its then/else elements.
     */
    private fun childrenOf(node: PsiElement): List<PsiElement> {
        val arguments = when (node) {
            is DartCallExpression -> node.arguments
            is DartNewExpression -> node.arguments
            is DartForElement -> return listOfNotNull(node.element)
            is DartIfElement -> return node.elementList
            else -> null
        }?.let(DartArguments::getArgumentList) ?: return emptyList()

        val positional = arguments.expressionList
            .filterIsInstance<DartListLiteralExpression>()
            .flatMap { it.elementList }
        val named = arguments.namedArgumentList
            .filter { it.parameterReferenceExpression?.text in CHILD_ARGUMENTS }
            .flatMap { argument ->
                when (val value = argument.expression) {
                    is DartListLiteralExpression -> value.elementList
                    null -> emptyList()
                    else -> listOf(argument)
                }
            }
        return (positional + named).sortedBy { it.textRange.startOffset }
    }

    /** `css(...)` rules nest lists of rules, not components. */
    private fun isStyleRule(node: PsiElement): Boolean {
        val callee = (node as? DartCallExpression)?.expression?.text ?: return false
        return callee == "css" || callee.startsWith("css.")
    }

    private fun isInComponentScope(node: PsiElement): Boolean {
        val function = PsiTreeUtil.getParentOfType(node, DartComponent::class.java) ?: return false
        if (function.name == "build") return true
        val returnType = PsiTreeUtil.getChildOfType(function, DartReturnType::class.java) ?: return false
        return "Component" in returnType.text
    }

    private fun firstCharOfLine(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
        return start
    }
}
