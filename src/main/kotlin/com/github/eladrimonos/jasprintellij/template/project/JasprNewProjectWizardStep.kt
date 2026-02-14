package com.github.eladrimonos.jasprintellij.template.project

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.jetbrains.lang.dart.sdk.DartSdk

class JasprNewProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private var org: String = "com.example"

    override fun setupUI(builder: Panel) {
        builder.row("Organization:") {
            textField().bindText(::org)
        }
    }

    override fun setupProject(project: Project) {
        ensureDartSdkConfigured(project)

        val baseDir = project.baseDir ?: return
        createFileIfMissing(baseDir, "pubspec.yaml", defaultPubspecYaml(project.name))
        createFileIfMissing(baseDir, "lib/main.dart", defaultMainDart())
    }

    private fun ensureDartSdkConfigured(project: Project) {
        if (!isDartSdkConfigured(project)) {
            throw ConfigurationException(
                "No se ha encontrado Dart SDK configurado.\n" +
                        "Configúralo en Settings | Languages & Frameworks | Dart."
            )
        }
    }

    private fun isDartSdkConfigured(project: Project): Boolean {
        // Compatibilidad entre versiones del plugin Dart (métodos pueden variar).
        val cls = DartSdk::class.java
        val sdk = runCatching {
            cls.methods.firstOrNull { m ->
                m.name == "getDartSdk" &&
                        m.parameterCount == 1 &&
                        m.parameterTypes[0].name == Project::class.java.name
            }?.invoke(null, project)
                ?: cls.methods.firstOrNull { m -> m.name == "getGlobalDartSdk" && m.parameterCount == 0 }
                    ?.invoke(null)
        }.getOrNull()

        return sdk != null
    }

    private fun createFileIfMissing(baseDir: VirtualFile, relativePath: String, content: String) {
        val existing = VfsUtil.findRelativeFile(baseDir, *relativePath.split("/").toTypedArray())
        if (existing != null && existing.exists()) return

        val parentPath = relativePath.substringBeforeLast("/", missingDelimiterValue = "")
        val fileName = relativePath.substringAfterLast("/")
        val parent = if (parentPath.isEmpty()) baseDir else VfsUtil.createDirectoryIfMissing(baseDir, parentPath)
        val created = parent.createChildData(this, fileName)
        VfsUtil.saveText(created, content)
    }

    private fun defaultPubspecYaml(projectName: String): String = """
        name: ${projectName.lowercase().replace(' ', '_')}
        description: A Jaspr app.
        publish_to: "none"
        version: 0.1.0

        environment:
          sdk: ">=3.0.0 <4.0.0"

        dependencies:
          jaspr: any
    """.trimIndent()

    private fun defaultMainDart(): String = """
        void main() {
          print('Hello Jaspr');
        }
    """.trimIndent()
}
