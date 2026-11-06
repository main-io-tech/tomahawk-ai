package com.mio.tomahawk.run

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Qual CLI de IA o Tomahawk usa e como, como no Interceptador do Iron Dome. A chave de API do Cursor fica no cofre
 * de senhas da IDE; o resto, nas preferências. Sem caminho configurado, o executável é procurado no PATH e nas
 * pastas de instalação. As missões só saem depois que o teste passa com a configuração de agora.
 */
object TomahawkSettings {

    private const val PROVIDER_KEY = "Tomahawk.Agent.Provider"
    private const val EXECUTABLE_KEY = "Tomahawk.Agent.Executable"
    private const val MODEL_KEY = "Tomahawk.Agent.Model"
    private const val TIMEOUT_KEY = "Tomahawk.Agent.TimeoutMinutes"
    private const val RUN_MODE_KEY = "Tomahawk.Agent.RunMode"
    private const val EXTRA_ARGS_KEY = "Tomahawk.Agent.ExtraArgs"
    private const val HOST_KEY = "Tomahawk.Agent.Host"
    private const val WSL_DISTRO_KEY = "Tomahawk.Agent.WslDistro"
    private const val KEY_VERSION_KEY = "Tomahawk.Agent.KeyVersion"
    private const val VERIFIED_KEY = "Tomahawk.Agent.Verified"
    private const val VERIFIED_AT_KEY = "Tomahawk.Agent.VerifiedAt"
    private const val TERMINAL_VERIFIED_KEY = "Tomahawk.Agent.TerminalVerified"
    private const val TERMINAL_VERIFIED_AT_KEY = "Tomahawk.Agent.TerminalVerifiedAt"
    private const val AUTO_SETUP_KEY = "Tomahawk.Agent.AutoSetupTried"
    private const val SPECIFY_KEY = "Tomahawk.Specify.Executable"
    private const val SPECIFY_SHELL_KEY = "Tomahawk.Specify.Shell"
    private const val GUARDRAILS_KEY = "Tomahawk.Agent.Guardrails"
    private val cursorCredentials = CredentialAttributes(generateServiceName("Tomahawk IA", "Cursor API key"))

    const val DEFAULT_TIMEOUT_MINUTES = 30

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    private val props: PropertiesComponent get() = PropertiesComponent.getInstance()

    var provider: AgentProvider
        get() = AgentProvider.entries.firstOrNull { it.name == props.getValue(PROVIDER_KEY) } ?: AgentProvider.CURSOR
        set(value) = change { props.setValue(PROVIDER_KEY, value.name, AgentProvider.CURSOR.name) }

    var host: AgentHost
        get() = AgentHost.entries.firstOrNull { it.name == props.getValue(HOST_KEY) } ?: AgentHost.LOCAL
        set(value) = change { props.setValue(HOST_KEY, value.name, AgentHost.LOCAL.name) }

    /** A distribuição do WSL, como `Ubuntu`, quando [host] é o WSL. */
    var wslDistro: String?
        get() = props.getValue(WSL_DISTRO_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(WSL_DISTRO_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /** Caminho do executável (no WSL, o caminho do Linux); vazio procura sozinho. */
    var executable: String?
        get() = props.getValue(EXECUTABLE_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(EXECUTABLE_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    var model: String?
        get() = props.getValue(MODEL_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(MODEL_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    var timeoutMinutes: Int
        get() = props.getInt(TIMEOUT_KEY, DEFAULT_TIMEOUT_MINUTES).coerceIn(1, 480)
        set(value) = change { props.setValue(TIMEOUT_KEY, value.coerceIn(1, 480), DEFAULT_TIMEOUT_MINUTES) }

    var runMode: RunMode
        get() = RunMode.entries.firstOrNull { it.name == props.getValue(RUN_MODE_KEY) } ?: RunMode.BACKGROUND
        set(value) = change { props.setValue(RUN_MODE_KEY, value.name, RunMode.BACKGROUND.name) }

    /** Argumentos a mais para o CLI, antes do prompt, como `--approve-mcps`. */
    var extraArgs: String?
        get() = props.getValue(EXTRA_ARGS_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(EXTRA_ARGS_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /** Caminho do `specify`; vazio procura no PATH e em `~/.local/bin`, onde o `uv tool install` o deixa. */
    var specifyExecutable: String?
        get() = props.getValue(SPECIFY_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(SPECIFY_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /** Onde rodam os comandos do Spec Kit no Windows: nulo é o shell padrão do terminal da IDE. */
    var specKitShell: WindowsShell?
        get() = WindowsShell.entries.firstOrNull { it.name == props.getValue(SPECIFY_SHELL_KEY) }
        set(value) = change { props.setValue(SPECIFY_SHELL_KEY, value?.name) }

    /** As proteções da máquina nas missões ([Guardrails] e [Checkpoints]); ligadas por padrão. */
    var guardrails: Boolean
        get() = props.getBoolean(GUARDRAILS_KEY, true)
        set(value) = change { props.setValue(GUARDRAILS_KEY, value, true) }

    /** A configuração automática roda sozinha só na primeira vez; depois, só pelo botão. */
    var autoSetupTried: Boolean
        get() = props.getBoolean(AUTO_SETUP_KEY)
        set(value) = props.setValue(AUTO_SETUP_KEY, value)

    /** Nada foi configurado à mão: sem executável, sem modelo e sem teste. */
    val untouched: Boolean get() = executable == null && model == null && props.getValue(VERIFIED_KEY) == null

    /**
     * Opcional: sem ela, o Cursor CLI usa o login do `agent login`. Trocar a chave muda a versão dela, o que
     * invalida o teste sem ler o cofre de senhas a cada atualização da tela. Fora da EDT.
     */
    var cursorApiKey: String?
        get() = PasswordSafe.instance.getPassword(cursorCredentials)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val key = value?.trim()?.takeIf { it.isNotEmpty() }
            if (key == cursorApiKey) return
            PasswordSafe.instance.setPassword(cursorCredentials, key)
            change { props.setValue(KEY_VERSION_KEY, props.getInt(KEY_VERSION_KEY, 0) + 1, 0) }
        }

    val hasCursorApiKey: Boolean get() = props.getInt(KEY_VERSION_KEY, 0) > 0

    @Volatile
    private var readyCache: Boolean? = null

    /** A IA pode ser usada: o executável existe e o teste passou com a configuração de agora. */
    val ready: Boolean
        get() = readyCache ?: (launch() != null && verified).also { readyCache = it }

    val verified: Boolean get() = props.getValue(VERIFIED_KEY) == fingerprint()

    val verifiedAt: String? get() = props.getValue(VERIFIED_AT_KEY)?.takeIf { verified }

    /** O que um teste valida: muda com o CLI, onde ele está e o executável, o modelo, a chave ou os argumentos extras. */
    fun fingerprint(): String = listOf(
        provider.name,
        launch()?.description.orEmpty(),
        model.orEmpty(),
        extraArgs.orEmpty(),
        props.getInt(KEY_VERSION_KEY, 0).toString(),
    ).joinToString("|")

    fun markVerified(at: String) = change {
        props.setValue(VERIFIED_KEY, fingerprint())
        props.setValue(VERIFIED_AT_KEY, at)
    }

    fun clearVerified() = change { props.unsetValue(VERIFIED_KEY) }

    /** Quando o teste pelo terminal passou com a configuração de agora; nulo se nunca, ou se ela mudou. */
    var terminalVerifiedAt: String?
        get() = props.getValue(TERMINAL_VERIFIED_AT_KEY)?.takeIf { props.getValue(TERMINAL_VERIFIED_KEY) == fingerprint() }
        set(value) = change {
            props.setValue(TERMINAL_VERIFIED_KEY, value?.let { fingerprint() })
            props.setValue(TERMINAL_VERIFIED_AT_KEY, value)
        }

    private fun change(update: () -> Unit) {
        update()
        readyCache = null
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(Listener.TOPIC)?.changed()
    }

    /**
     * Como rodar o CLI com a configuração dada; nulo se ele não existe nesta máquina ou falta a distribuição do WSL.
     * No WSL não roda nada: sem caminho configurado, vale o nome do comando, achado pelo shell de login.
     */
    fun launch(
        provider: AgentProvider = this.provider,
        configured: String? = executable,
        host: AgentHost = this.host,
        distro: String? = wslDistro,
    ): AgentLaunch? = when (host) {
        AgentHost.LOCAL -> resolveExecutable(provider, configured)?.let(AgentLaunch::local)
        AgentHost.WSL -> distro?.takeIf { AgentLaunch.wslSupported }?.let { AgentLaunch.wsl(it, configured ?: provider.executables.first()) }
    }

    /** O executável configurado, se existir, ou o encontrado no PATH e nas pastas de instalação. */
    fun resolveExecutable(provider: AgentProvider = this.provider, configured: String? = executable): Path? {
        configured?.let { path -> return Path.of(path).takeIf(Files::isRegularFile) }
        return find(provider)
    }

    fun find(provider: AgentProvider): Path? = find(provider.executables, installDirs(provider))

    fun resolveSpecify(configured: String? = specifyExecutable): Path? {
        configured?.let { path -> return Path.of(path).takeIf(Files::isRegularFile) }
        val home = System.getProperty("user.home")
        return find(listOf("specify"), listOf(File(home, ".local/bin")))
    }

    /** O `uv`, que instala o `specify`: no PATH ou onde o instalador da Astral o deixa. */
    fun resolveUv(): Path? {
        val home = System.getProperty("user.home")
        return find(listOf("uv"), listOf(File(home, ".local/bin"), File(home, ".cargo/bin")))
    }

    private fun find(executables: List<String>, dirs: List<File>): Path? {
        val names = executables.flatMap { name ->
            if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        }
        names.firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it) }?.let { return it.toPath() }
        return dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile }?.toPath()
    }

    /** Onde os instaladores costumam deixar o CLI quando a IDE não herdou o PATH do terminal. */
    private fun installDirs(provider: AgentProvider): List<File> {
        val home = System.getProperty("user.home")
        val localAppData = System.getenv("LOCALAPPDATA")
        val appData = System.getenv("APPDATA")
        return when (provider) {
            AgentProvider.CURSOR -> listOfNotNull(
                File(home, ".local/bin"),
                File(home, ".cursor/bin"),
                localAppData?.let { File(it, "cursor-agent") },
                localAppData?.let { File(it, "Programs/cursor-agent") },
            )
            AgentProvider.CLAUDE -> listOfNotNull(
                File(home, ".local/bin"),
                File(home, ".claude/local"),
                appData?.let { File(it, "npm") },
            )
        }
    }
}
