package com.mio.tomahawk.run

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * O terminal da IDE, para a missão rodar à vista do usuário. Vem do plugin de terminal, que é opcional: sem ele,
 * não há extensão e as missões rodam só na aba.
 */
interface LaunchTerminal {

    /** O shell que o terminal do projeto abre, como `powershell.exe` ou `/bin/zsh`. Fora da EDT. */
    fun shellPath(project: Project): String?

    /** Na EDT. Abre uma aba nova do terminal em [workDir], chamada [title], e executa [command] nela. */
    fun run(project: Project, workDir: String, title: String, command: String)

    companion object {
        val EP = ExtensionPointName<LaunchTerminal>("com.mio.tomahawk.launchTerminal")

        fun available(): LaunchTerminal? = EP.extensionList.firstOrNull()
    }
}
