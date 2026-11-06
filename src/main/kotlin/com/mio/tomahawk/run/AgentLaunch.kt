package com.mio.tomahawk.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Onde o CLI de IA está instalado. */
enum class AgentHost(val label: String) {
    LOCAL("Nesta máquina"),
    WSL("No WSL"),
}

/**
 * Como rodar o CLI: direto nesta máquina, dentro de uma distribuição do WSL, pelo shell de login dela (que põe
 * `~/.local/bin` no PATH), ou no devcontainer do projeto, nas missões na aba. [executable] é o caminho ou o nome do
 * comando no sistema onde ele roda.
 */
class AgentLaunch private constructor(val executable: String, val distro: String?, private val container: DevContainer.Session? = null) {

    private val wsl: WSLDistribution? by lazy { distro?.let { WslDistributionManager.getInstance().getOrCreateDistributionByMsId(it) } }

    val description: String
        get() = when {
            container != null -> "devcontainer: $executable"
            distro != null -> "WSL $distro: $executable"
            else -> executable
        }

    /** Para as mensagens: " no WSL Ubuntu", " no devcontainer" ou vazio. */
    val where: String
        get() = when {
            container != null -> " no devcontainer"
            distro != null -> " no WSL $distro"
            else -> ""
        }

    /** Um caminho desta máquina como o CLI o enxerga: igual, `/mnt/c/...` no WSL ou `/workspaces/...` no container. */
    fun workspace(path: String): String = container?.path(path) ?: wsl?.getWslPath(Path.of(path)) ?: path

    /**
     * O processo de [command] (que começa pelo [executable]) em [workDir], com [env] no ambiente. Com [scrub], nesta
     * máquina, o processo não herda os segredos do ambiente ([Guardrails.environment]); o WSL já não herda o do
     * Windows, e o container só recebe [env].
     */
    fun process(command: List<String>, workDir: String, env: Map<String, String>, scrub: Boolean = false): GeneralCommandLine {
        container?.let { return it.exec(command, env) }
        val line = GeneralCommandLine(command)
            .withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
        if (scrub && distro == null) {
            line.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(Guardrails.environment(EnvironmentUtil.getEnvironmentMap(), env))
        } else {
            line.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE).withEnvironment(env)
        }
        val distribution = wsl ?: return line
        val options = WSLCommandLineOptions()
            .setExecuteCommandInShell(true)
            .setExecuteCommandInLoginShell(true)
            .setExecuteCommandInInteractiveShell(true)
            .setRemoteWorkingDirectory(workspace(workDir))
            .setPassEnvVarsUsingInterop(true)
        return distribution.patchCommandLine(line, null, options)
    }

    /** Para a IA de vez: no container, derruba o container, porque matar o processo daqui não a para lá dentro. */
    fun stop() {
        container?.stop()
    }

    /**
     * O comando de uma linha para o terminal da IDE, que roda em [shell] nesta máquina. Com [output], a resposta vai
     * para esse arquivo, relativo a [workDir].
     */
    fun terminal(
        provider: AgentProvider,
        prompt: String,
        model: String?,
        extraArgs: List<String>,
        shell: ShellKind,
        workDir: String,
        env: Map<String, String> = emptyMap(),
        interactive: Boolean = true,
        settings: Path? = null,
        output: String? = null,
    ): String {
        val rules = settings?.let { workspace(it.toString()) }
        val distribution = distro ?: return AgentCommand.terminal(provider, executable, prompt, model, extraArgs, shell, env, interactive, rules, output)
        val inner = AgentCommand.terminal(provider, executable, prompt, model, extraArgs, ShellKind.POSIX, env, interactive, rules, output)
        return AgentCommand.wrapWsl(distribution, workspace(workDir), inner, shell)
    }

    companion object {
        private const val FIND_TIMEOUT_MS = 20_000

        fun local(executable: Path) = AgentLaunch(executable.toString(), null)

        fun wsl(distro: String, executable: String) = AgentLaunch(executable, distro)

        /** O CLI no container de pé, onde [executable] é o nome do comando no PATH do container. */
        fun container(session: DevContainer.Session, executable: String) = AgentLaunch(executable, null, session)

        /** Windows com WSL: só aí a opção aparece. */
        val wslSupported: Boolean get() = SystemInfo.isWindows && runCatching { WSLUtil.isSystemCompatible() }.getOrDefault(false)

        /** As distribuições instaladas, sem as internas do Docker Desktop; roda o `wsl.exe`, então fora da EDT. */
        fun distributions(): List<String> =
            if (!wslSupported) emptyList()
            else runCatching { WslDistributionManager.getInstance().installedDistributions.map { it.msId } }.getOrDefault(emptyList())
                .filterNot { it.startsWith("docker-desktop", ignoreCase = true) }

        /** O caminho do CLI no PATH do shell de login de [distro], ou nulo; fora da EDT. */
        fun findInWsl(provider: AgentProvider, distro: String): String? {
            val lookup = provider.executables.joinToString(" || ") { "command -v $it" }
            val line = GeneralCommandLine("wsl.exe", "-d", distro, "--", "bash", "-lic", lookup).withCharset(StandardCharsets.UTF_8)
            val output = runCatching { ExecUtil.execAndGetOutput(line, FIND_TIMEOUT_MS) }.getOrNull() ?: return null
            return output.stdoutLines.map { it.trim() }.lastOrNull { it.startsWith("/") }
        }
    }
}
