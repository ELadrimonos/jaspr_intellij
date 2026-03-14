package com.github.eladrimonos.jasprintellij.module

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
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

        // 2. Apply Dart/Jaspr configuration to every module (idempotent).
        for (module in moduleManager.modules) {
            configureModule(module, projectDir)
        }
    }

    private fun configureModule(module: Module, projectDir: File) {
        // Enable the Dart SDK so the IDE indexes sources correctly.
        DartSdkLibUtil.enableDartSdk(module)

        val model = ModuleRootManager.getInstance(module).modifiableModel
        try {
            for (contentEntry in model.contentEntries) {
                val entryUrl = contentEntry.url
                val existingExcludes = contentEntry.excludeFolders.map { it.url }.toSet()

                // Standard Dart/Jaspr directories that should be excluded from indexing.
                for (dir in listOf(".dart_tool", ".pub", "build")) {
                    val url = "$entryUrl/$dir"
                    if (url !in existingExcludes) {
                        contentEntry.addExcludeFolder(url)
                    }
                }

                // Mark "web/images" as a resource root when it exists,
                // so the IDE handles static assets correctly.
                val imagesDir = File(projectDir, "web/images")
                val imagesVf = LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(imagesDir)

                if (imagesVf != null) {
                    val alreadyMarked = contentEntry.sourceFolders
                        .any { it.file?.path == imagesVf.path }
                    if (!alreadyMarked) {
                        contentEntry.addSourceFolder(imagesVf, JavaResourceRootType.RESOURCE)
                    }
                }
            }
            model.commit()
        } catch (e: Exception) {
            model.dispose()
            throw e
        }
    }
}