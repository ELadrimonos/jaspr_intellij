package com.github.eladrimonos.jasprintellij.module

import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.lang.dart.sdk.DartSdkLibUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.io.File

object JasprModuleConfigurator {

    /**
     * Ensures the project has at least one module and all modules are
     * properly configured for Dart/Jaspr.
     *
     * Safe to call from any thread. Dispatches write actions correctly.
     * Designed to be re-executed on project open (idempotent).
     */
    fun ensureConfigured(project: Project) {
        val app = ApplicationManager.getApplication()

        // Always schedule on EDT; never block if we're already on EDT.
        val runnable = Runnable {
            app.runWriteAction {
                if (project.isDisposed) return@runWriteAction
                doEnsureConfigured(project)
            }
        }

        if (app.isDispatchThread) {
            runnable.run()
        } else {
            app.invokeAndWait(runnable)
        }
    }

    private fun doEnsureConfigured(project: Project) {
        val basePath = project.basePath ?: return
        val projectDir = File(basePath)
        val moduleManager = ModuleManager.getInstance(project)

        // 1. Create a default module if none exists.
        //    This happens after wizard-based creation in IntelliJ IDEA,
        //    where the new-project framework does NOT auto-create a module
        //    for GeneratorNewProjectWizard implementations.
        if (moduleManager.modules.isEmpty()) {
            val ideaDir = File(projectDir, ".idea").also { it.mkdirs() }
            val imlPath = File(ideaDir, "${project.name}.iml").path

            // "WEB_MODULE" is recognised by both IntelliJ IDEA and WebStorm
            // and gives us full Dart/web indexing without requiring the Java plugin.
            val module = moduleManager.newModule(imlPath, "WEB_MODULE")

            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            val projectVf = LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(projectDir)
            if (projectVf != null) {
                rootModel.addContentEntry(projectVf)
            }
            rootModel.commit()
        }

        // 2. Apply Dart/Jaspr configuration to the module(s) containing the Jaspr
        //    package (idempotent). Other modules in the project are left alone.
        val jasprDir = JasprTooling.findJasprProjectDir(project) ?: projectDir
        for (module in moduleManager.modules) {
            if (!containsDir(module, jasprDir)) continue
            configureModule(module, jasprDir)
        }
    }

    private fun containsDir(module: Module, dir: File): Boolean {
        val dirVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) ?: return false
        return ModuleRootManager.getInstance(module).contentRoots.any { VfsUtilCore.isAncestor(it, dirVf, false) }
    }

    private fun configureModule(module: Module, jasprDir: File) {
        // Enable the Dart SDK so the IDE indexes sources correctly.
        if (!DartSdkLibUtil.isDartSdkEnabled(module)) {
            DartSdkLibUtil.enableDartSdk(module)
        }

        val model = ModuleRootManager.getInstance(module).modifiableModel
        try {
            val jasprVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jasprDir) ?: run {
                model.dispose()
                return
            }
            // Static assets: mark "web/images" as a resource root when it exists.
            val imagesVf = LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(File(jasprDir, "web/images"))

            for (contentEntry in model.contentEntries) {
                val entryFile = contentEntry.file ?: continue
                if (!VfsUtilCore.isAncestor(entryFile, jasprVf, false)) continue

                val existingExcludes = contentEntry.excludeFolders.map { it.url }.toSet()

                // Standard Dart/Jaspr directories that should be excluded from indexing.
                for (dir in listOf(".dart_tool", ".pub", "build")) {
                    val url = "${jasprVf.url}/$dir"
                    if (url !in existingExcludes) {
                        contentEntry.addExcludeFolder(url)
                    }
                }

                if (imagesVf != null) {
                    val alreadyMarked = contentEntry.sourceFolders
                        .any { it.file?.path == imagesVf.path }
                    if (!alreadyMarked) {
                        contentEntry.addSourceFolder(imagesVf, JavaResourceRootType.RESOURCE)
                    }
                }
            }

            // Committing an unchanged model would still fire rootsChanged and could trigger
            // reindexing on every project open; only commit real changes.
            if (model.isChanged) model.commit() else model.dispose()
        } catch (e: Exception) {
            model.dispose()
            throw e
        }
    }
}
