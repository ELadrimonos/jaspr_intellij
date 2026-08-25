package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Regression test for the "open editor keeps showing stale content until you switch
 * tabs" bug: [MelosScriptInjector] must update an already-loaded [com.intellij.openapi.editor.Document]
 * directly, not just the bytes on disk, or an open editor never repaints on its own.
 */
class MelosScriptInjectorDocumentSyncTest : BasePlatformTestCase() {

    fun testEditingAnAlreadyOpenDocumentUpdatesItImmediately() {
        val basePath = project.basePath ?: error("Test project has no base path")
        val ioFile = File(basePath, "pubspec.yaml")
        ioFile.writeText("name: my_workspace\n")

        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("Could not find a VirtualFile for $ioFile")
        // Loading (and thus caching) the Document simulates the file being open in an editor.
        val document = FileDocumentManager.getInstance().getDocument(vFile)
            ?: error("Could not load a Document for $vFile")

        MelosScriptInjector.ensureScript(ioFile, "serve_jaspr", "website")

        assertTrue(
            "The already-loaded Document must reflect the injected script immediately, " +
                "without requiring an external VFS refresh or editor tab switch",
            document.text.contains("serve_jaspr:"),
        )
        assertEquals("Document content must match what's now on disk", ioFile.readText(), document.text)
    }
}
