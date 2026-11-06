package com.mio.tomahawk.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** [LaunchTerminal] pelo plugin de terminal da IDE; registrado em `tomahawk-terminal.xml`. */
class IdeLaunchTerminal : LaunchTerminal {

    override fun shellPath(project: Project): String? =
        runCatching { TerminalProjectOptionsProvider.getInstance(project).shellPath }.getOrNull()

    override fun run(project: Project, workDir: String, title: String, command: String, shell: WindowsShell?) {
        val manager = TerminalToolWindowManager.getInstance(project)
        if (shell == null) {
            manager.createShellWidget(workDir, title, true, false).sendCommandToExecute(command)
            return
        }
        val state = TerminalTabState().apply {
            myTabName = title
            myIsUserDefinedTabTitle = true
            myWorkingDirectory = workDir
            myShellCommand = shell.startup(command)
        }
        val open = Runnable { manager.createNewSession(manager.terminalRunner, state) }
        ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.activate(open) ?: open.run()
    }
}
