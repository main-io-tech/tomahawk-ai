package com.mio.tomahawk.run

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** [LaunchTerminal] pelo plugin de terminal da IDE; registrado em `tomahawk-terminal.xml`. */
class IdeLaunchTerminal : LaunchTerminal {

    override fun shellPath(project: Project): String? =
        runCatching { TerminalProjectOptionsProvider.getInstance(project).shellPath }.getOrNull()

    override fun run(project: Project, workDir: String, title: String, command: String) {
        val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(workDir, title, true, false)
        widget.sendCommandToExecute(command)
    }
}
