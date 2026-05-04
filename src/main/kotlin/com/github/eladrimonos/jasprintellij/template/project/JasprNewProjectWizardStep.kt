package com.github.eladrimonos.jasprintellij.template.project

import com.github.eladrimonos.jasprintellij.actions.JasprUpdateCliAction
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationSetup
import com.github.eladrimonos.jasprintellij.icons.JasprIcons
import com.github.eladrimonos.jasprintellij.module.JasprModuleConfigurator
import com.github.eladrimonos.jasprintellij.services.JasprCliMissingException
import com.github.eladrimonos.jasprintellij.services.JasprProjectCreator
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.Panel
import com.jetbrains.lang.dart.sdk.DartSdkLibUtil
import com.jetbrains.lang.dart.sdk.DartSdkUtil
import java.io.File

/**
 * Last step of the Jaspr new-project wizard in **IntelliJ IDEA**.
 *
 * Delegates all UI to [JasprSettingsPanel] so the layout is shared with
 * [JasprDirectoryProjectGenerator] (WebStorm / small IDEs) without duplication.
 */
class JasprNewProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private val settingsPanel = JasprSettingsPanel(project = context.project)
    private val projectCreator = JasprProjectCreator()

    // ── UI ───────────────────────────────────────────────────────────────────

    override fun setupUI(builder: Panel) {
        // Inline the shared rows directly into the wizard panel.
        settingsPanel.buildInto(builder)
    }

    // ── Project creation ─────────────────────────────────────────────────────

    override fun setupProject(project: Project) {
        val projectDir = File(
            project.basePath ?: throw ConfigurationException("Project path not found")
        )
        runCreation(project, projectDir, settingsPanel, projectCreator)
    }

    companion object {
        /**
         * Shared creation logic used by both the IDEA wizard step and the
         * WebStorm generator so both paths behave identically.
         */
        fun runCreation(
            project: Project,
            projectDir: File,
            panel: JasprSettingsPanel,
            creator: JasprProjectCreator = JasprProjectCreator(),
        ) {
            val sdkPath = panel.sdkPath

            if (!DartSdkUtil.isDartSdkHome(sdkPath)) {
                throw ConfigurationException("Invalid Dart SDK path selected.")
            }

            PropertiesComponent.getInstance().setValue("dart.sdk.path", sdkPath)

            // Configure the Dart SDK on the EDT *before* spawning the background task
            // so it is available when the project opens.
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    DartSdkLibUtil.ensureDartSdkConfigured(project, sdkPath)
                }
            }

            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    try {
                        creator.preflightCheck(sdkPath)
                    } catch (e: JasprCliMissingException) {
                        ApplicationManager.getApplication().invokeLater {
                            val notification = NotificationGroupManager.getInstance()
                                .getNotificationGroup("JasprIntelliJ")
                                .createNotification(
                                    "Jaspr Tooling Error",
                                    "Preflight check failed: ${e.localizedMessage?.substringBefore("\n") ?: "Unknown error"}",
                                    NotificationType.ERROR
                                )
                                .setIcon(JasprIcons.JasprLogo)

                            notification.addAction(NotificationAction.create("Install jaspr_cli") { _, notification ->
                                JasprUpdateCliAction().runAction(project) {
                                    // Optionally start daemon here too if needed, though usually new project 
                                    // creation will trigger its own start later.
                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        project.getService(JasprToolingDaemonService::class.java)?.start()
                                    }
                                }
                                notification.expire()
                            })

                            notification.notify(project)
                        }
                        throw e
                    }

                    creator.create(projectDir = projectDir, sdkPath = sdkPath, options = panel.toCreatorOptions())

                    // addIfNeeded does I/O — keep it here on the background thread.
                    JasprRunConfigurationSetup.addIfNeeded(project, projectDir)

                    ApplicationManager.getApplication().invokeAndWait {
                        LocalFileSystem.getInstance()
                            .refreshAndFindFileByIoFile(projectDir)
                            ?.refresh(false, true)
                        JasprModuleConfigurator.ensureConfigured(project)
                    }
                },
                "Creating Jaspr Project…",
                false,
                project,
            )
        }
    }
}
