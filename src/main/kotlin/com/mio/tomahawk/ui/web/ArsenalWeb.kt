package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.mio.tomahawk.run.AgentAutoSetup
import com.mio.tomahawk.run.AgentCommand
import com.mio.tomahawk.run.AgentHost
import com.mio.tomahawk.run.AgentLaunch
import com.mio.tomahawk.run.AgentProvider
import com.mio.tomahawk.run.AgentVerifier
import com.mio.tomahawk.run.Guardrails
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService

/**
 * O Arsenal: a configuração da IA, como o Interceptador do Iron Dome. Configuração automática, os campos do CLI
 * (onde está, executável, modelo, tempo, argumentos e a chave do Cursor), Salvar e Testar (sem o teste as missões
 * não saem), onde lançar com o teste pelo terminal, e o Spec Kit do projeto.
 */
class ArsenalWeb(project: Project) : WebTab(project) {

    override val accent = "teal"

    /** O que está nos campos e ainda não foi salvo. */
    private data class Draft(
        val provider: AgentProvider,
        val host: AgentHost,
        val distro: String?,
        val executable: String,
        val model: String,
        val timeout: Int,
        val extra: String,
    ) {
        companion object {
            fun current() = Draft(
                TomahawkSettings.provider,
                TomahawkSettings.host,
                TomahawkSettings.wslDistro,
                TomahawkSettings.executable.orEmpty(),
                TomahawkSettings.model.orEmpty(),
                TomahawkSettings.timeoutMinutes,
                TomahawkSettings.extraArgs.orEmpty(),
            )
        }
    }

    private class Status(val text: String, val ok: Boolean?)

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private var draft = Draft.current()
    private var saving = false
    private var distros: List<String> = emptyList()
    private val wslFound = mutableMapOf<String, String?>()
    private var testStatus: Status? = null
    private var autoStatus: Status? = null
    private var autoFailure: String? = null
    private var terminalStatus: Status? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (!saving && !AgentAutoSetup.running) draft = Draft.current()
                    refresh()
                }, project.disposed)
            },
        )
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refresh() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SpecKitSetup.Listener.TOPIC,
            SpecKitSetup.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        if (AgentLaunch.wslSupported) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = AgentLaunch.distributions()
                ApplicationManager.getApplication().invokeLater({
                    distros = names
                    if (draft.distro == null && names.isNotEmpty()) draft = draft.copy(distro = names.first())
                    refresh()
                }, project.disposed)
            }
        }
        refresh()
        if (!TomahawkSettings.ready && !TomahawkSettings.autoSetupTried && TomahawkSettings.untouched) {
            ApplicationManager.getApplication().invokeLater({ autoSetup() }, project.disposed)
        }
    }

    override fun body(): String = buildString {
        val ready = TomahawkSettings.ready
        val launch = TomahawkSettings.launch()
        val busy = AgentAutoSetup.running || AgentVerifier.testing
        val (title, badge) = when {
            AgentAutoSetup.running -> "Configurando a IA…" to "<span class=\"status-big warn\">⏳ configurando</span>"
            AgentVerifier.testing -> "Testando a integração…" to "<span class=\"status-big warn\">⏳ testando</span>"
            launch == null -> "IA não configurada" to "<span class=\"status-big bad\">○ não configurada</span>"
            !ready -> "Teste a integração" to "<span class=\"status-big warn\">⚠ falta testar</span>"
            else -> "IA pronta para o lançamento" to "<span class=\"status-big good\">✓ pronta</span>"
        }
        val subtitle = when {
            launch == null -> esc(TomahawkSettings.provider.installHint)
            else -> esc("${TomahawkSettings.provider.title} CLI${launch.where} · ${TomahawkSettings.model?.let { "modelo $it" } ?: "modelo padrão"}") +
                (TomahawkSettings.verifiedAt?.let { " · testada em ${esc(it)}" } ?: "")
        }
        append(WebUi.header("🛠️", "Arsenal · configuração da IA", title, subtitle, "", badge))
        append("<div class=\"grid2\" style=\"margin-top:14px;align-items:start\"><div>")
        append(autoCard(busy))
        append(cliCard(busy))
        append("</div><div>")
        append(runCard())
        append(guardCard())
        append(specKitCard())
        append(capsCard(ready))
        append("</div></div>")
    }

    private fun autoCard(busy: Boolean): String = buildString {
        append("<div class=\"card2\"><h3>⚡ Configuração automática</h3>")
        append("<div class=\"desc\">Procura o Cursor CLI e o Claude Code instalados")
        if (AgentLaunch.wslSupported) append(", nesta máquina e no WSL,")
        append(" e testa cada um, com o modelo padrão, até um responder.</div>")
        append(WebUi.button(if (AgentAutoSetup.running) "⏳ Configurando…" else "✨ Configurar automaticamente", "auto", "primary", enabled = !busy))
        status(autoStatus)
        autoFailure?.let { append("<div class=\"notice warn\"><span class=\"ni\">⚠️</span><div class=\"nb\">").append(esc(it)).append("</div></div>") }
        append("</div>")
    }

    private fun cliCard(busy: Boolean): String = buildString {
        val d = draft
        append("<div class=\"card2\" data-form=\"cli\"><h3>🤖 CLI de IA</h3>")
        append("<div class=\"desc\">O agente que executa os comandos /speckit sobre o projeto aberto.</div>")
        append("<div class=\"field\"><label>CLI</label>")
        append(WebUi.seg("provider", AgentProvider.entries.map { it.name to "${it.title} <span class=\"muted\">(${it.executables.first()})</span>" }, d.provider.name, send = "form"))
        append("</div>")
        if (AgentLaunch.wslSupported) {
            append("<div class=\"field\"><label>Onde está o CLI</label>")
            append(WebUi.seg("host", AgentHost.entries.map { it.name to it.label }, d.host.name, send = "form")).append("</div>")
            if (d.host == AgentHost.WSL) {
                append("<div class=\"field\"><label>Distribuição do WSL</label><select class=\"in\" name=\"distro\" data-change=\"form\">")
                (distros + listOfNotNull(d.distro)).distinct().forEach { name ->
                    append("<option value=\"").append(WebUi.attr(name)).append("\"").append(if (name == d.distro) " selected" else "").append(">").append(esc(name)).append("</option>")
                }
                if (distros.isEmpty() && d.distro == null) append("<option value=\"\">Procurando distribuições…</option>")
                append("</select></div>")
            }
        } else {
            append("<input type=\"hidden\" name=\"host\" value=\"LOCAL\">")
        }
        append("<div class=\"field\"><label>Executável</label><div class=\"in-row\">")
        append("<input class=\"in\" name=\"executable\" data-change=\"form\" value=\"").append(WebUi.attr(d.executable))
        append("\" placeholder=\"").append(if (d.host == AgentHost.WSL) "Vazio usa ${d.provider.executables.first()} do PATH do WSL" else "Vazio procura sozinho").append("\">")
        if (d.host == AgentHost.LOCAL) append(WebUi.button("📂 Procurar…", "browse", "ghost"))
        append("</div>").append(foundHint(d)).append("</div>")
        append("<div class=\"cols\">")
        append("<div class=\"field\"><label>Modelo</label><input class=\"in\" name=\"model\" value=\"").append(WebUi.attr(d.model)).append("\" placeholder=\"Padrão do CLI\">")
        append("<span class=\"hint\">Como no --model do CLI.</span></div>")
        append("<div class=\"field\"><label>Tempo máximo (min)</label><input class=\"in\" type=\"number\" min=\"1\" max=\"480\" name=\"timeout\" value=\"${d.timeout}\">")
        append("<span class=\"hint\">Uma missão que passar disso é abortada.</span></div>")
        append("</div>")
        append("<div class=\"field\"><label>Argumentos extras</label><input class=\"in\" name=\"extra\" value=\"").append(WebUi.attr(d.extra)).append("\" placeholder=\"Ex.: --approve-mcps\"></div>")
        if (d.provider == AgentProvider.CURSOR) {
            append("<div class=\"field\"><label>Chave de API do Cursor</label><input class=\"in\" type=\"password\" name=\"key\" autocomplete=\"off\" placeholder=\"")
            append(if (TomahawkSettings.hasCursorApiKey) "•••••••• salva no cofre de senhas (deixe vazio para manter)" else "Opcional: sem ela, vale o agent login").append("\">")
            append("<span class=\"hint\">Crie em cursor.com/dashboard › Integrations. Fica no cofre de senhas da IDE.</span>")
            if (TomahawkSettings.hasCursorApiKey) append("<div>").append(WebUi.button("Remover chave salva", "clearKey", "ghost")).append("</div>")
            append("</div>")
        }
        append("<div class=\"notice warn\" style=\"margin:0 0 12px\"><span class=\"ni\">🔐</span><div class=\"nb\">")
        append("<b>Missões na aba:</b> o Cursor roda com <code>--force --trust</code> e o Claude Code com <code>--permission-mode bypassPermissions</code>, ")
        append("como no despacho de workflows do Spec Kit: a IA roda scripts e escreve arquivos sem perguntar, com as permissões do seu usuário. ")
        append("O cartão <b>Proteções</b> barra o que pode danificar a máquina e guarda um ponto de restauração. Para aprovar cada passo, lance no terminal da IDE.<br>")
        append("<b>Textos</b> (commit, PR, respostas do Rádio) e o teste: só leitura, com o Cursor em <code>--mode ask</code> e o Claude Code em ")
        append("<code>--permission-mode dontAsk</code> com <code>--allowedTools ${AgentCommand.READ_TOOLS}</code>. ")
        append("Assim, um comentário malicioso num PR não dá à IA permissão para alterar arquivos.</div></div>")
        append("<div class=\"row-btns\">")
        append(WebUi.button(if (AgentVerifier.testing) "⏳ Testando…" else "✔ Salvar e testar", "save", "primary big", arg = "test", enabled = !busy))
        append(WebUi.button("Salvar", "save", enabled = !busy))
        append("</div>")
        status(testStatus ?: TomahawkSettings.verifiedAt?.takeIf { TomahawkSettings.ready }?.let { Status("Testada em $it.", true) })
        append("</div>")
    }

    private fun foundHint(d: Draft): String {
        if (d.host == AgentHost.WSL) {
            val distro = d.distro ?: return "<span class=\"hint bad\">Escolha a distribuição do WSL.</span>"
            if (d.executable.isNotBlank()) return "<span class=\"hint\">No WSL $distro: ${esc(d.executable)}</span>"
            val key = "${d.provider.name}|$distro"
            if (key !in wslFound) {
                wslFound[key] = null
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentLaunch.findInWsl(d.provider, distro)
                    ApplicationManager.getApplication().invokeLater({
                        wslFound[key] = path ?: ""
                        refresh()
                    }, project.disposed)
                }
                return "<span class=\"hint\">Procurando o ${esc(d.provider.title)} CLI no WSL $distro…</span>"
            }
            val found = wslFound[key]
            return when {
                found == null -> "<span class=\"hint\">Procurando o ${esc(d.provider.title)} CLI no WSL $distro…</span>"
                found.isEmpty() -> "<span class=\"hint bad\">Não encontrado no WSL $distro. ${esc(d.provider.installHint)}</span>"
                else -> "<span class=\"hint good\">✓ Encontrado: WSL $distro: ${esc(found)}</span>"
            }
        }
        val path = TomahawkSettings.resolveExecutable(d.provider, d.executable.ifBlank { null })
        return if (path != null) "<span class=\"hint good\">✓ Encontrado: ${esc(path.toString())}</span>"
        else "<span class=\"hint bad\">Não encontrado. ${esc(d.provider.installHint)}</span>"
    }

    private fun runCard(): String = buildString {
        val terminal = LaunchTerminal.available() != null
        append("<div class=\"card2\"><h3>🎛️ Lançamento</h3><div class=\"desc\">Onde as missões rodam por padrão; cada etapa pode trocar na hora de lançar.</div>")
        append("<div class=\"field\"><label>Onde lançar</label>")
        append(WebUi.seg("runMode", RunMode.entries.map { it.name to it.label }, TomahawkSettings.runMode.name, send = "runmode")).append("</div>")
        append("<div class=\"desc\">Na aba, a Telemetria acompanha cada passo e a IA decide sozinha quando o comando faria perguntas. ")
        append("No terminal da IDE você conversa com a IA (o Reconhecimento já abre lá).</div>")
        append(WebUi.button(if (AgentVerifier.testingTerminal) "⏳ Esperando o terminal…" else "💻 Testar no terminal", "terminal",
            enabled = terminal && !AgentVerifier.testingTerminal && TomahawkSettings.launch() != null,
            title = if (terminal) "Abre o terminal da IDE e roda o CLI com a configuração salva" else "O plugin de terminal da IDE está desligado"))
        status(terminalStatus ?: TomahawkSettings.terminalVerifiedAt?.let { Status("Terminal testado em $it.", true) })
        append("</div>")
    }

    private fun guardCard(): String = buildString {
        val on = TomahawkSettings.guardrails
        val cursor = TomahawkSettings.provider == AgentProvider.CURSOR
        append("<div class=\"card2\"><h3>🛡️ Proteções</h3><div class=\"desc\">O que impede a IA, rodando sozinha, de danificar a máquina ou perder o seu trabalho.</div>")
        append("<div class=\"field\">").append(WebUi.seg("guard", listOf("on" to "🛡️ Ligadas", "off" to "Desligadas"), if (on) "on" else "off", send = "guard")).append("</div>")
        if (!on) {
            append("<div class=\"notice err\" style=\"margin:0 0 10px\"><span class=\"ni\">⚠️</span><div class=\"nb\">Com as proteções desligadas, a IA roda qualquer comando ")
            append("com as permissões do seu usuário e as missões não têm ponto de restauração.</div></div>")
        }
        append("<ul class=\"caps\">")
        listOf(
            "<b>Barrado antes de rodar</b>: apagar pastas inteiras, formatar discos, desligar a máquina, pedir administrador, mudar o sistema, " +
                "instalar programas, rodar script baixado da internet, <code>git push</code>/<code>reset --hard</code>/<code>clean</code>. " +
                (if (cursor) "Fica no <code>.cursor/cli.json</code> do projeto." else "Vai no <code>--settings</code> do Claude Code, que também não escreve fora do projeto."),
            "<b>Vigia</b>: o Tomahawk lê cada passo da IA e aborta a missão num comando perigoso, numa escrita fora do projeto ou dentro do <code>.git</code>, " +
                "ou numa leitura de credenciais (<code>~/.ssh</code>, <code>~/.aws</code>…).",
            "<b>Ponto de restauração</b>: antes de cada missão, o projeto inteiro vai para o Git (em <code>refs/tomahawk/</code>, sem mexer na sua branch), e dá para desfazer.",
            "<b>Sem segredos</b>: tokens e chaves do ambiente (GitHub, nuvem, npm) não chegam ao processo da IA.",
        ).forEach { text ->
            append("<li><span class=\"ck ").append(if (on) "on\">✓" else "off\">○").append("</span><span>").append(text).append("</span></li>")
        }
        append("</ul><div class=\"row-btns\" style=\"margin-top:10px\">")
        append(WebUi.button("↩ Desfazer a última missão", "undo", "ghost", enabled = runner.progress == null,
            title = "Volta o projeto para o ponto de restauração da última missão, depois de mostrar o que mudou"))
        append("</div><div class=\"status-line\">Nenhuma proteção substitui uma máquina isolada: para tarefas arriscadas, rode a IA num container, numa VM ou no WSL.</div>")
        append("</div>")
    }

    private fun specKitCard(): String = buildString {
        val s = service.snapshot
        val specify = TomahawkSettings.resolveSpecify()
        val info = SpecKitSetup.infoFor(specify)
        val installing = SpecKitSetup.job != null
        val integration = TomahawkSettings.provider.integration
        append("<div class=\"card2\"><h3>🌱 Spec Kit</h3><div class=\"desc\">O CLI <code>specify</code> nesta máquina e os comandos instalados neste projeto.</div>")
        append("<div class=\"chips\" style=\"margin:0 0 10px\">")
        append(
            when {
                specify == null -> WebUi.chip("Máquina", "specify não instalado", "red")
                info?.outdated == true -> WebUi.chip("Máquina", "specify v${esc(info.version ?: "?")} · nova v${esc(info.latest.orEmpty())}", "orange")
                else -> WebUi.chip("Máquina", "specify ${info?.version?.let { "v" + esc(it) } ?: ""} ✓", "green")
            },
        )
        append(if (s.initialized) WebUi.chip("Projeto", "inicializado", "green") else WebUi.chip("Projeto", "sem .specify/", "yellow"))
        s.integration.key?.let { append(WebUi.chip("Integração", esc(it))) }
        if (s.initialized) append(WebUi.chip("Comandos", "${s.commands.size}/${SddStep.entries.size}", if (s.commands.size == SddStep.entries.size) "green" else "orange"))
        append("</div>")
        specify?.let { append("<div class=\"status-line\">").append(esc(it.toString())).append("</div>") }
        append(SpecKitWeb.machine(specify))
        val key = s.integration.key
        if (s.initialized && key != null && key != integration) {
            append("<div class=\"notice warn\"><span class=\"ni\">⚠️</span><div class=\"nb\">O projeto foi inicializado para <b>").append(esc(key))
            append("</b>, mas a IA configurada é o <b>").append(esc(TomahawkSettings.provider.title)).append("</b>, e os comandos dele podem não estar instalados.")
            append("<div class=\"cmdline\" style=\"margin:8px 0 0\">specify integration install ").append(esc(integration)).append("</div><div class=\"row-btns\">")
            append(WebUi.button("🔌 Instalar a integração ${esc(integration)}", "specify-integration", enabled = specify != null && !installing))
            append("</div></div></div>")
        }
        if (!s.initialized) {
            append("<div class=\"notice warn\"><span class=\"ni\">🛰️</span><div class=\"nb\">Este projeto ainda não tem o Spec Kit (a pasta <b>.specify/</b>).")
            append("<div class=\"cmdline\" style=\"margin:8px 0 0\">specify init --here --integration ").append(esc(integration)).append("</div><div class=\"row-btns\">")
            append(
                WebUi.button(
                    "⚡ Instalar no projeto", "init", "primary", enabled = specify != null && !installing,
                    title = if (specify == null) "Instale o Spec Kit nesta máquina primeiro" else "Roda specify init --here no terminal da IDE",
                ),
            )
            append("</div></div></div>")
        }
        if (specify != null && !installing) {
            append("<div class=\"row-btns\" style=\"margin-top:10px\">")
            append(WebUi.button("↻ Verificar de novo", "specify-recheck", "ghost", title = "Procura o specify de novo, relê a versão e o projeto"))
            append("</div>")
        }
        append("</div>")
    }

    private fun capsCard(ready: Boolean): String = buildString {
        val terminal = LaunchTerminal.available() != null
        append("<div class=\"card2\"><h3>✨ O que a IA libera</h3><ul class=\"caps\">")
        listOf(
            ready to "<b>Lançador</b>: as nove etapas do SDD na aba, monitoradas, ou no terminal",
            (ready && terminal) to "<b>Terminal</b>: conversar com a IA nas etapas que fazem perguntas",
            ready to "<b>Avaliação</b>: Simulação de Rota (analyze) e Avaliação de Danos (converge) num clique",
            true to "<b>Telemetria</b>: cada passo da IA ao vivo e o registro de cada missão",
        ).forEach { (on, text) ->
            append("<li><span class=\"ck ").append(if (on) "on\">✓" else "off\">○").append("</span><span>").append(text).append("</span></li>")
        }
        append("</ul>")
        if (!ready) append("<div class=\"status-line\">Desligadas até a integração ser testada com a configuração atual.</div>")
        append("</div>")
    }

    private fun StringBuilder.status(status: Status?) {
        status ?: return
        append("<div class=\"status-line ").append(
            when (status.ok) {
                true -> "good"
                false -> "bad"
                null -> ""
            },
        ).append("\">").append(esc(status.text)).append("</div>")
    }

    private fun readForm(data: JsonObject) {
        draft = draft.copy(
            provider = AgentProvider.entries.firstOrNull { it.name == data.str("provider") } ?: draft.provider,
            host = AgentHost.entries.firstOrNull { it.name == data.str("host") } ?: draft.host,
            distro = data.str("distro")?.ifBlank { null } ?: draft.distro,
            executable = data.str("executable") ?: draft.executable,
            model = data.str("model") ?: draft.model,
            timeout = data.int("timeout") ?: draft.timeout,
            extra = data.str("extra") ?: draft.extra,
        )
    }

    private fun save(key: String?, test: Boolean) {
        saving = true
        val d = draft
        TomahawkSettings.provider = d.provider
        TomahawkSettings.host = if (AgentLaunch.wslSupported) d.host else AgentHost.LOCAL
        TomahawkSettings.wslDistro = d.distro
        TomahawkSettings.executable = d.executable
        TomahawkSettings.model = d.model
        TomahawkSettings.timeoutMinutes = d.timeout
        TomahawkSettings.extraArgs = d.extra
        testStatus = Status(if (test) "Testando o ${d.provider.title}…" else "Salvo.", if (test) null else true)
        refresh(force = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!key.isNullOrBlank()) TomahawkSettings.cursorApiKey = key
            ApplicationManager.getApplication().invokeLater({
                saving = false
                draft = Draft.current()
                if (!test) return@invokeLater refresh(force = true)
                AgentVerifier.test { outcome ->
                    testStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
                refresh(force = true)
            }, project.disposed)
        }
    }

    private fun autoSetup() {
        autoFailure = null
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus = Status(message, null)
                refresh(force = true)
            },
            onDone = { result ->
                draft = Draft.current()
                autoStatus = Status(if (result.ok) result.message else "Não deu para configurar sozinho: termine na configuração manual.", result.ok)
                autoFailure = result.message.takeUnless { result.ok }
                testStatus = null
                refresh(force = true)
            },
        )
        refresh(force = true)
    }

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "form" -> {
                readForm(data)
                refresh(force = true)
            }
            "browse" -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA")
                FileChooser.chooseFile(descriptor, project, null)?.let {
                    readForm(data)
                    draft = draft.copy(executable = it.path)
                    refresh(force = true)
                }
            }
            "save" -> {
                readForm(data)
                save(data.str("key"), test = data.str("arg") == "test")
            }
            "clearKey" -> ApplicationManager.getApplication().executeOnPooledThread { TomahawkSettings.cursorApiKey = null }
            "auto" -> autoSetup()
            "runmode" -> RunMode.entries.firstOrNull { it.name == data.str("value") }?.let { TomahawkSettings.runMode = it }
            "terminal" -> {
                terminalStatus = Status("Abrindo o terminal e esperando a IA gravar OK…", null)
                refresh(force = true)
                AgentVerifier.testTerminal(project) { outcome ->
                    terminalStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
            }
            "init" -> runner.initSpecKit()
            "specify-install" -> SpecKitSetup.install(project)
            "specify-upgrade" -> SpecKitSetup.upgrade(project)
            "specify-recheck" -> {
                SpecKitSetup.recheck()
                service.refresh(0)
            }
            "specify-integration" -> SpecKitSetup.installIntegration(project)
            "guard" -> {
                val on = data.str("value") == "on"
                TomahawkSettings.guardrails = on
                service.root()?.let { root ->
                    ApplicationManager.getApplication().executeOnPooledThread { runCatching { Guardrails.applyCursorConfig(root, on) } }
                }
                refresh(force = true)
            }
            "undo" -> runner.undo()
        }
    }
}
