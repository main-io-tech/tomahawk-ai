package com.mio.tomahawk.run

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/**
 * O terminal da IDE, para a missão rodar à vista do usuário. Vem do plugin de terminal, que é opcional: sem ele,
 * não há extensão e as missões rodam só na aba.
 */
interface LaunchTerminal {

    /** O shell que o terminal do projeto abre, como `powershell.exe` ou `/bin/zsh`. Fora da EDT. */
    fun shellPath(project: Project): String?

    /**
     * Na EDT. Abre uma aba nova do terminal em [workDir], chamada [title], e executa [command] nela: no shell padrão
     * do terminal ou, com [shell], numa aba desse shell do Windows.
     */
    fun run(project: Project, workDir: String, title: String, command: String, shell: WindowsShell? = null)

    companion object {
        val EP = ExtensionPointName<LaunchTerminal>("com.mio.tomahawk.launchTerminal")

        fun available(): LaunchTerminal? = EP.extensionList.firstOrNull()
    }
}

/** Um shell do Windows para os comandos do Spec Kit, no lugar do shell padrão do terminal da IDE. */
enum class WindowsShell(val label: String, val kind: ShellKind) {
    POWERSHELL("PowerShell", ShellKind.POWERSHELL),
    CMD("CMD", ShellKind.CMD);

    /** O shell que já abre rodando [command], escrito para [kind], e continua aberto para o usuário ver o resultado. */
    fun startup(command: String): List<String> = when (this) {
        POWERSHELL -> {
            val script = "Write-Host ${AgentCommand.quote("> $command", ShellKind.POWERSHELL)} -ForegroundColor DarkGray; $command"
            listOf("powershell.exe", "-NoLogo", "-NoExit", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE)))
        }
        CMD -> {
            val file = Files.createTempFile("tomahawk-", ".cmd")
            Files.writeString(file, "@chcp 65001 > nul\r\n$command\r\n", StandardCharsets.UTF_8)
            file.toFile().deleteOnExit()
            listOf("cmd.exe", "/k", file.toString())
        }
    }
}
