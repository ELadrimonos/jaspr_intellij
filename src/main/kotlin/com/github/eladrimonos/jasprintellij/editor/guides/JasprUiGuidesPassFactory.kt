package com.github.eladrimonos.jasprintellij.editor.guides

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.jetbrains.lang.dart.psi.DartFile

class JasprUiGuidesPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

    override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? =
        if (file is DartFile) JasprUiGuidesPass(file, editor) else null
}

/** Keeps one highlighter per guide in the editor, replacing them only when the guides change. */
private class JasprUiGuidesPass(private val file: PsiFile, private val editor: Editor) :
    TextEditorHighlightingPass(file.project, editor.document, false) {

    private var guides: List<JasprUiGuide> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        guides = JasprUiGuides.forFile(file)
    }

    override fun doApplyInformationToEditor() {
        val applied = editor.getUserData(APPLIED_GUIDES)
        if (applied != null && applied.guides == guides && applied.highlighters.all { it.isValid }) return
        applied?.dispose()

        val length = document.textLength
        val markup = editor.markupModel
        val highlighters = guides.filter { it.endOffset <= length }.map { guide ->
            val children = guide.childOffsets.map { document.createRangeMarker(it, it) }
            markup.addRangeHighlighter(
                guide.startOffset,
                guide.endOffset,
                HighlighterLayer.FIRST,
                null,
                HighlighterTargetArea.EXACT_RANGE,
            ).apply { customRenderer = JasprUiGuideRenderer(children) }
        }
        editor.putUserData(APPLIED_GUIDES, if (highlighters.isEmpty()) null else AppliedGuides(guides, highlighters))
    }

    private class AppliedGuides(val guides: List<JasprUiGuide>, val highlighters: List<RangeHighlighter>) {
        fun dispose() {
            highlighters.forEach {
                (it.customRenderer as? JasprUiGuideRenderer)?.dispose()
                it.dispose()
            }
        }
    }

    companion object {
        private val APPLIED_GUIDES = Key.create<AppliedGuides>("jaspr.ui.guides")
    }
}
