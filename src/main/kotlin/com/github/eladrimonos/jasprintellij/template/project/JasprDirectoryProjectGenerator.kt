package com.github.eladrimonos.jasprintellij.template.project

import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.ide.util.projectWizard.WebProjectTemplate
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ProjectGeneratorPeer
import com.jetbrains.lang.dart.sdk.DartSdkUtil
import java.io.File
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class JasprDirectoryProjectGenerator : WebProjectTemplate<JasprSettingsPanel>() {

    // ── Identity ─────────────────────────────────────────────────────────────

    override fun getName(): String = "Jaspr"

    override fun getDescription(): String =
        "Create a new <b>Jaspr</b> web project — a Dart framework for " +
                "building reactive, isomorphic web UIs."

    override fun getIcon(): Icon = JasprIcons.JasprLogo

    // ── Peer ─────────────────────────────────────────────────────────────────

    override fun createPeer(): ProjectGeneratorPeer<JasprSettingsPanel> {
        val panel = JasprSettingsPanel()

        return object : ProjectGeneratorPeer<JasprSettingsPanel> {

            // The listener is stored so we can fire it when the SDK field changes,
            // which tells WebStorm to re-run validate() and update the Create button.
            private var settingsListener: ProjectGeneratorPeer.SettingsListener? = null

            // Modern platform entry point (2023.1+)
            override fun getComponent(
                myLocationField: TextFieldWithBrowseButton,
                checkValid: Runnable,
            ): JComponent {
                attachSdkFieldListener(panel, checkValid)
                return panel.component
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun getComponent(): JComponent = panel.component

            override fun buildUI(settingsStep: SettingsStep) {
                settingsStep.addSettingsComponent(panel.component)
            }

            override fun getSettings(): JasprSettingsPanel = panel

            override fun validate(): ValidationInfo? = validateToInfo(panel)

            override fun isBackgroundJobRunning(): Boolean = false

            override fun addSettingsListener(listener: ProjectGeneratorPeer.SettingsListener) {
                settingsListener = listener
                // Also wire the SDK field so typing triggers the listener immediately.
                attachSdkDocumentListener(panel) { listener.stateChanged(true) }
            }
        }
    }

    // ── Generation ───────────────────────────────────────────────────────────

    override fun generateProject(
        project: Project,
        baseDir: VirtualFile,
        settings: JasprSettingsPanel,
        module: Module,
    ) {
        JasprNewProjectWizardStep.runCreation(
            project = project,
            projectDir = File(baseDir.path),
            panel = settings,
            creator = JasprProjectCreator(),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    companion object {

        fun validateToInfo(panel: JasprSettingsPanel): ValidationInfo? {
            val path = panel.sdkPath
            return when {
                path.isBlank() ->
                    ValidationInfo("Dart SDK path is required.")
                !DartSdkUtil.isDartSdkHome(path) ->
                    ValidationInfo(
                        "Invalid Dart SDK path. Must contain bin/dart. " +
                                "If you use Flutter, try: flutter/bin/cache/dart-sdk"
                    )
                else -> null
            }
        }

        private fun attachSdkDocumentListener(panel: JasprSettingsPanel, onChanged: () -> Unit) {
            panel.sdkTextField?.document?.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onChanged()
                override fun removeUpdate(e: DocumentEvent) = onChanged()
                override fun changedUpdate(e: DocumentEvent) = onChanged()
            })
        }

        private fun attachSdkFieldListener(panel: JasprSettingsPanel, checkValid: Runnable) {
            attachSdkDocumentListener(panel) { checkValid.run() }
        }
    }
}