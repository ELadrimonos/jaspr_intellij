package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/**
 * Run/Debug tool window toolbar actions that send `client.restart` to the
 * running `jaspr daemon`. Only the client app is reloaded this way — the server
 * and generated styles are reloaded by the "Auto action" (`--auto`) on save.
 */
class JasprHotReloadAction(
    private val handler: JasprDaemonProcessHandler,
    private val fullRestart: Boolean,
    text: String,
    description: String,
    icon: Icon,
) : DumbAwareAction(text, description, icon) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !handler.isProcessTerminating &&
            !handler.isProcessTerminated &&
            handler.hasConnectedApps
    }

    override fun actionPerformed(e: AnActionEvent) {
        handler.restartClients(fullRestart)
    }

    companion object {
        /**
         * @param supportsHotReload whether the daemon implements hot reload
         *   (`client.restart` with `fullRestart: false`); jaspr_cli < 0.24 only does restarts.
         */
        fun create(handler: JasprDaemonProcessHandler, supportsHotReload: Boolean) = listOfNotNull(
            JasprHotReloadAction(
                handler,
                fullRestart = false,
                text = JasprBundle.message("jaspr.action.hot.reload.text"),
                description = JasprBundle.message("jaspr.action.hot.reload.description"),
                icon = AllIcons.Actions.Lightning,
            ).takeIf { supportsHotReload },
            JasprHotReloadAction(
                handler,
                fullRestart = true,
                text = JasprBundle.message("jaspr.action.hot.restart.text"),
                description = JasprBundle.message("jaspr.action.hot.restart.description"),
                icon = AllIcons.Actions.Restart,
            ),
        ).toTypedArray()
    }
}
