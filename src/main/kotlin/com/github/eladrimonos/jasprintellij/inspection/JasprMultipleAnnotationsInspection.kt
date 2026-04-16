package com.github.eladrimonos.jasprintellij.inspection

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.psi.DartMetadata
import com.jetbrains.lang.dart.psi.DartVisitor

class JasprMultipleAnnotationsInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : DartVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DartFile) return

                val clientAnnotations = mutableListOf<DartMetadata>()

                SyntaxTraverser.psiTraverser(file)
                    .filter(DartMetadata::class.java)
                    .forEach { metadata ->
                        val text = metadata.text
                        if (text == "@client" || text.startsWith("@client(")) {
                            clientAnnotations.add(metadata)
                        }
                    }

                if (clientAnnotations.size > 1) {
                    clientAnnotations.forEach {
                        holder.registerProblem(it, JasprBundle.message("jaspr.inspection.multiple.client.annotations"))
                    }
                }
            }
        }
    }
}
