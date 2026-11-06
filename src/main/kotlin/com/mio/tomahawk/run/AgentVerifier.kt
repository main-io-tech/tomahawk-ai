package com.mio.tomahawk.run

import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Testa a integração com a configuração salva: uma pergunta curta, sem alterar arquivos, que confirma o executável,
 * o login e o modelo. Se passar, a IA fica marcada como testada e os botões de lançar se habilitam.
 */
object AgentVerifier {

    class Outcome(val ok: Boolean, val message: String)

    private const val TIMEOUT_MS = 120_000
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val AUTH_REQUIRED = Regex("""authenticat|not logged in|login required|unauthori[sz]ed|invalid api key|/login""", RegexOption.IGNORE_CASE)

    @Volatile
    var testing = false
        private set

    @Volatile
    var testingTerminal = false
        private set

    /** [onDone] roda na EDT. */
    fun test(onDone: (Outcome) -> Unit) {
        val provider = TomahawkSettings.provider
        val launch = TomahawkSettings.launch()
        if (launch == null) {
            TomahawkSettings.clearVerified()
            val missing = if (TomahawkSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado. ${provider.installHint}"
            onDone(Outcome(false, missing))
            return
        }
        val model = TomahawkSettings.model
        val extra = AgentCommand.splitArgs(TomahawkSettings.extraArgs)
        testing = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val workspace = System.getProperty("user.home")
            val env = apiKeyEnv(provider)
            val outcome = runCatching {
                val command = AgentCommand.headless(provider, launch.executable, "Responda apenas OK", launch.workspace(workspace), model, extra, readOnly = true)
                ExecUtil.execAndGetOutput(launch.process(command, workspace, env), TIMEOUT_MS)
            }.fold(
                onSuccess = { output ->
                    val events = output.stdoutLines.flatMap(AgentEvents::parse)
                    val answer = events.filterIsInstance<AgentEvent.Result>().firstOrNull()
                    val reportedModel = events.filterIsInstance<AgentEvent.Init>().firstOrNull()?.model
                    val reported = answer?.takeIf { it.error }?.text?.takeIf { it.isNotBlank() }
                    when {
                        output.isTimeout -> Outcome(false, "Sem resposta em ${TIMEOUT_MS / 60_000} minutos.")
                        output.exitCode != 0 -> Outcome(
                            false,
                            explain(provider, reported ?: output.stderrLines.lastOrNull { it.isNotBlank() } ?: "O CLI terminou com o código ${output.exitCode}."),
                        )
                        answer == null -> Outcome(
                            false,
                            "O CLI respondeu, mas sem o resultado esperado. Confira se ele está atualizado" +
                                (output.stdoutLines.lastOrNull { it.isNotBlank() }?.let { ": a última linha foi \"${it.take(120)}\"" } ?: "."),
                        )
                        answer.error -> Outcome(false, explain(provider, answer.text.ifBlank { "A IA informou um erro." }))
                        else -> Outcome(
                            true,
                            "${provider.title}${launch.where}${(reportedModel ?: model)?.let { " · modelo $it" }.orEmpty()} respondeu: ${answer.text.trim().take(40)}",
                        )
                    }
                },
                onFailure = { Outcome(false, it.message ?: it.javaClass.simpleName) },
            )
            ApplicationManager.getApplication().invokeLater({
                testing = false
                if (outcome.ok) TomahawkSettings.markVerified(LocalDateTime.now().format(DATE)) else TomahawkSettings.clearVerified()
                onDone(outcome)
            }, ModalityState.any())
        }
    }

    /** A chave do Cursor no ambiente, se houver; fora da EDT, porque lê o cofre de senhas. */
    fun apiKeyEnv(provider: AgentProvider): Map<String, String> =
        if (provider == AgentProvider.CURSOR) TomahawkSettings.cursorApiKey?.let { mapOf("CURSOR_API_KEY" to it) }.orEmpty() else emptyMap()

    /** O erro do CLI com o que fazer, quando ele é conhecido, como o login que falta. */
    fun explain(provider: AgentProvider, message: String): String = when {
        AUTH_REQUIRED.containsMatchIn(message) -> when (provider) {
            AgentProvider.CURSOR -> "$message. Falta entrar no Cursor: rode agent login no terminal ou informe uma chave de API, depois teste de novo."
            AgentProvider.CLAUDE -> "$message. Falta entrar no Claude Code: rode claude uma vez no terminal e faça o login, depois teste de novo."
        }
        else -> message
    }

    /**
     * Testa o caminho do terminal da IDE com a configuração salva, no Windows ou no WSL: abre uma aba do terminal,
     * pergunta à IA, só leitura, e o shell grava a resposta num arquivo, que o teste espera. Não muda o teste
     * principal. [onDone] roda na EDT.
     */
    fun testTerminal(project: Project, onDone: (Outcome) -> Unit) {
        val terminal = LaunchTerminal.available() ?: return onDone(Outcome(false, "O plugin de terminal da IDE está desligado."))
        val provider = TomahawkSettings.provider
        val launch = TomahawkSettings.launch()
            ?: return onDone(Outcome(false, if (TomahawkSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado."))
        val root = project.basePath ?: return onDone(Outcome(false, "O projeto não tem pasta no disco."))
        val dir = Path.of(root, RunStore.DIR, "testes")
        val file = dir.resolve("teste-terminal-${System.currentTimeMillis()}.md")
        val relative = Path.of(root).relativize(file).toString().replace('\\', '/')
        testingTerminal = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val shellPath = terminal.shellPath(project)
            val shell = ShellKind.of(shellPath, SystemInfo.isWindows)
            val prompt = "Teste de integracao do Tomahawk IA: responda apenas OK, sem fazer mais nada."
            val command = runCatching {
                launch.terminal(provider, prompt, TomahawkSettings.model, AgentCommand.splitArgs(TomahawkSettings.extraArgs), shell, root, interactive = false, output = relative)
            }
            ApplicationManager.getApplication().invokeLater({
                try {
                    Files.createDirectories(dir)
                    terminal.run(project, root, "Tomahawk: Teste", command.getOrThrow())
                } catch (e: Exception) {
                    testingTerminal = false
                    onDone(Outcome(false, "O terminal não abriu: ${e.message ?: e.javaClass.simpleName}"))
                    return@invokeLater
                }
                val shellName = shellPath?.replace('\\', '/')?.substringAfterLast('/') ?: shell.name.lowercase()
                waitForTerminalAnswer(file, provider, launch, shellName, onDone)
            }, ModalityState.any())
        }
    }

    private fun waitForTerminalAnswer(file: Path, provider: AgentProvider, launch: AgentLaunch, shellName: String, onDone: (Outcome) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()
            var answer: String? = null
            while (System.currentTimeMillis() - started < TIMEOUT_MS) {
                if (Files.isRegularFile(file)) {
                    answer = runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    if (answer != null) break
                }
                Thread.sleep(1_000)
            }
            runCatching { Files.deleteIfExists(file) }
            val seconds = (System.currentTimeMillis() - started) / 1000
            val outcome = if (answer == null) {
                Outcome(false, "A resposta não chegou em ${TIMEOUT_MS / 60_000} minutos. Confira a aba \"Tomahawk: Teste\" do terminal: o comando, o login e o PATH do $shellName.")
            } else {
                Outcome(true, "${provider.title}${launch.where} respondeu no terminal ($shellName) em $seconds s.")
            }
            ApplicationManager.getApplication().invokeLater({
                testingTerminal = false
                if (outcome.ok) TomahawkSettings.terminalVerifiedAt = LocalDateTime.now().format(DATE)
                onDone(outcome)
            }, ModalityState.any())
        }
    }
}
