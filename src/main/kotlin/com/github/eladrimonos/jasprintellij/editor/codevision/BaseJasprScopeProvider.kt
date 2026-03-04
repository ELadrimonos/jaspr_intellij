package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.lang.dart.psi.DartClassDefinition
import java.awt.event.MouseEvent
import java.net.URI

abstract class BaseJasprScopeProvider : DaemonBoundCodeVisionProvider {

    override val groupId: String = "jaspr.component.scopes"

    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = when (id) {
            "jaspr.component.scopes.server" ->
                listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("jaspr.component.scopes.client"))

            "jaspr.component.scopes.client" ->
                listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("jaspr.component.scopes.hint"))

            else -> emptyList()
        }
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    override fun computeForEditor(
        editor: Editor,
        file: PsiFile
    ): List<Pair<TextRange, CodeVisionEntry>> {

        val project = editor.project ?: return emptyList()
        val daemonService = project.getService(JasprToolingDaemonService::class.java) ?: return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()

        val scopeData = daemonService.getScopesForFile(virtualFile.path) ?: return emptyList()
        if (scopeData.components.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<TextRange, CodeVisionEntry>>()

        runReadAction {
            SyntaxTraverser.psiTraverser(file)
                .filter(DartClassDefinition::class.java)
                .forEach { element ->

                    val className = element.componentName.text
                    if (className != null && className in scopeData.components) {
                        val range = element.componentName.textRange

                        when (id) {
                            "jaspr.component.scopes.server" -> {
                                if (scopeData.serverScopeRoots.isNotEmpty()) {
                                    val url = scopeData.serverScopeRoots.first().toNavigationUrl()
                                    results.add(
                                        range to ClickableTextCodeVisionEntry(
                                            text = JasprBundle.message("jaspr.component.scopes.server.label"),
                                            providerId = id,
                                            icon = JasprIcons.Server,
                                            onClick = { _: MouseEvent?, ed: Editor ->
                                                JasprScopeNavigationHandler.navigate(ed, url)
                                            }
                                        )
                                    )
                                }
                            }

                            "jaspr.component.scopes.client" -> {
                                if (scopeData.clientScopeRoots.isNotEmpty()) {
                                    val url = scopeData.clientScopeRoots.first().toNavigationUrl()
                                    results.add(
                                        range to ClickableTextCodeVisionEntry(
                                            text = JasprBundle.message("jaspr.component.scopes.client.label"),
                                            providerId = id,
                                            icon = JasprIcons.Client,
                                            onClick = { _: MouseEvent?, ed: Editor ->
                                                JasprScopeNavigationHandler.navigate(ed, url)
                                            }
                                        )
                                    )
                                }
                            }

                            "jaspr.component.scopes.hint" -> {
                                results.add(
                                    range to ClickableTextCodeVisionEntry(
                                        text = JasprBundle.message("jaspr.component.scopes.hint.label"),
                                        providerId = id,
                                        icon = JasprIcons.Question,
                                        onClick = { _: MouseEvent?, ed: Editor ->
                                            JasprScopeNavigationHandler.navigate(ed, HINT_URL)
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
        }

        return results
    }

    private fun JasprToolingDaemonService.ScopeTarget.toNavigationUrl(): String =
        url ?: "file://$path#$line"

    companion object {
        private const val HINT_URL =
            "https://docs.jaspr.site/dev/server#component-scopes"
    }
}

class JasprScopeNavigationHandler {
    companion object {
        fun navigate(editor: Editor, raw: String) {
            val project = editor.project ?: return

            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                BrowserUtil.browse(raw)
                return
            }

            try {
                val hashIdx = raw.lastIndexOf('#')
                val pathPart = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw
                val linePart = if (hashIdx >= 0) raw.substring(hashIdx + 1).toIntOrNull() else null
                val cleanPath = if (pathPart.startsWith("file://")) URI(pathPart).path else pathPart
                val vFile = LocalFileSystem.getInstance().findFileByPath(cleanPath) ?: return
                val descriptor = if (linePart != null) {
                    OpenFileDescriptor(project, vFile, linePart - 1, 0)
                } else {
                    OpenFileDescriptor(project, vFile)
                }
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            } catch (e: Exception) {
                // Ignore navigation errors
            }
        }
    }
}