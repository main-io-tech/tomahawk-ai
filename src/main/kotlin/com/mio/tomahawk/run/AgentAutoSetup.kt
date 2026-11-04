package com.mio.tomahawk.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * Configura a IA sozinha, como no Iron Dome: procura os CLIs instalados nesta máquina, Cursor primeiro, e depois nas
 * distribuições do WSL, e testa cada um com o modelo padrão até um responder. Se nenhum passar, a configuração fica
 * no primeiro encontrado e o motivo de cada falha vai no resultado, para terminar a configuração manual dali.
 */
object AgentAutoSetup {

    class Result(val ok: Boolean, val message: String)

    private class Candidate(val provider: AgentProvider, val host: AgentHost, val distro: String?, val where: String) {
        val title: String get() = "${provider.title} CLI${distro?.let { " no WSL $it" }.orEmpty()}"
    }

    @Volatile
    var running = false
        private set

    /** [onProgress] e [onDone] rodam na EDT. */
    fun run(onProgress: (String) -> Unit, onDone: (Result) -> Unit) {
        if (running || AgentVerifier.testing) return
        running = true
        TomahawkSettings.autoSetupTried = true
        onProgress("Procurando os CLIs de IA instalados…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val local = AgentProvider.entries.mapNotNull { provider ->
                TomahawkSettings.find(provider)?.let { Candidate(provider, AgentHost.LOCAL, null, it.toString()) }
            }
            val wsl = if (!AgentLaunch.wslSupported) emptyList() else {
                ApplicationManager.getApplication().invokeLater({ onProgress("Procurando os CLIs de IA no WSL…") }, ModalityState.any())
                AgentLaunch.distributions().flatMap { distro ->
                    AgentProvider.entries.mapNotNull { provider ->
                        AgentLaunch.findInWsl(provider, distro)?.let { Candidate(provider, AgentHost.WSL, distro, "WSL $distro: $it") }
                    }
                }
            }
            val found = local + wsl
            ApplicationManager.getApplication().invokeLater({
                if (found.isEmpty()) finish(onDone, Result(false, notFound())) else tryNext(found, 0, emptyList(), onProgress, onDone)
            }, ModalityState.any())
        }
    }

    private fun tryNext(found: List<Candidate>, index: Int, failures: List<String>, onProgress: (String) -> Unit, onDone: (Result) -> Unit) {
        if (index == found.size) {
            use(found.first())
            val missing = AgentProvider.entries.filter { provider -> found.none { it.provider == provider } }
            finish(onDone, Result(false, (failures + missing.map { "${it.title} CLI não encontrado." }).joinToString(" ")))
            return
        }
        val candidate = found[index]
        use(candidate)
        onProgress("Testando o ${candidate.title} em ${candidate.where}…")
        AgentVerifier.test { outcome ->
            if (outcome.ok) finish(onDone, Result(true, "Configurada com o ${candidate.title}. ${outcome.message}"))
            else tryNext(found, index + 1, failures + "${candidate.title}: ${outcome.message}", onProgress, onDone)
        }
    }

    private fun use(candidate: Candidate) {
        TomahawkSettings.provider = candidate.provider
        TomahawkSettings.host = candidate.host
        candidate.distro?.let { TomahawkSettings.wslDistro = it }
        TomahawkSettings.executable = null
        TomahawkSettings.model = null
    }

    private fun finish(onDone: (Result) -> Unit, result: Result) {
        running = false
        onDone(result)
    }

    private fun notFound(): String =
        "Nenhum CLI de IA encontrado${if (AgentLaunch.wslSupported) ", nem no WSL" else ""}. " +
            AgentProvider.entries.joinToString(" ") { "${it.title}: ${it.installHint}" }
}
