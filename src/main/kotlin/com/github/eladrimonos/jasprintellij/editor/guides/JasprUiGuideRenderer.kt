package com.github.eladrimonos.jasprintellij.editor.guides

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.paint.LinePainter2D
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Paints one UI guide: a vertical line below the component's line and a branch to each
 * child. Positions come from the highlighter and the child markers, so the guide keeps
 * following the code while it is edited, until the next highlighting pass rebuilds it.
 */
internal class JasprUiGuideRenderer(private val children: List<RangeMarker>) : CustomHighlighterRenderer {

    companion object {
        private val LINE_COLOR = JBColor(Gray._150, Gray._110)

        /** Where the guide sits relative to the indent column, like the platform's indent guides. */
        private const val GUIDE_SHIFT = 2

        /** Gap left between a branch and the child it points at. */
        private const val BRANCH_PADDING = 4
    }

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val document = editor.document
        val folding = editor.foldingModel

        val line = document.getLineNumber(highlighter.startOffset)
        val indentOffset = firstCharOfLine(document, line)
        if (folding.isOffsetCollapsed(indentOffset)) return

        val childOffsets = children.filter { it.isValid }.map { it.startOffset }.filter { offset ->
            val childLine = document.getLineNumber(offset)
            childLine > line && offset == firstCharOfLine(document, childLine) && !folding.isOffsetCollapsed(offset)
        }
        if (childOffsets.isEmpty()) return

        val lineHeight = editor.lineHeight
        val x = editor.offsetToXY(indentOffset).x + GUIDE_SHIFT
        val top = editor.visualLineToY(editor.offsetToVisualLine(document.getLineEndOffset(line), false) + 1)
        val bottom = editor.offsetToXY(childOffsets.last()).y + lineHeight / 2
        if (bottom <= top) return

        val g2d = g.create() as Graphics2D
        try {
            g2d.color = LINE_COLOR
            LinePainter2D.paint(g2d, x.toDouble(), top.toDouble(), x.toDouble(), bottom.toDouble())
            for (offset in childOffsets) {
                val child = editor.offsetToXY(offset)
                val end = child.x - BRANCH_PADDING
                if (end <= x) continue
                val y = (child.y + lineHeight / 2).toDouble()
                LinePainter2D.paint(g2d, x.toDouble(), y, end.toDouble(), y)
            }
        } finally {
            g2d.dispose()
        }
    }

    fun dispose() {
        children.forEach(RangeMarker::dispose)
    }

    private fun firstCharOfLine(document: Document, line: Int): Int {
        val chars = document.charsSequence
        val end = document.getLineEndOffset(line)
        var offset = document.getLineStartOffset(line)
        while (offset < end && (chars[offset] == ' ' || chars[offset] == '\t')) offset++
        return offset
    }
}
