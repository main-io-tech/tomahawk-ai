package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.delivery.DeliveryMode
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryWriter
import com.mio.tomahawk.delivery.PrRadarService
import com.mio.tomahawk.delivery.PullRequestDraft
import com.mio.tomahawk.delivery.PullRequestService
import com.mio.tomahawk.delivery.WriteTarget
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitLayout
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepState
import com.mio.tomahawk.speckit.StepStatus
import com.mio.tomahawk.ui.openArtifact
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Timer

/**
 * O Lançador na versão web: o cabeçalho da missão com o anel das etapas, as features em cartões e a linha do tempo
 * das nove etapas, com a etapa escolhida aberta para escrever a entrada e lançar.
 */
class LauncherWeb(project: Project) : WebTab(project) {

    override val accent = "orange"

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private var selected: SddStep? = null
    private var newFeature = false
    private var lastActive: Path? = null
    private val drafts = mutableMapOf<SddStep, String>()
    private val modes = mutableMapOf<SddStep, RunMode>()
    private val timer = Timer(TICK_MS) { runner.progress?.let { js("tmk.flight(${Flight.state(it)})") } }
    private val pr = PullRequestService.getInstance(project)
    /** O que o usuário escreveu no PR (branch, base, título, descrição, commit), por cima dos valores tirados da feature. */
    private val prFields = mutableMapOf<String, String>()
    private var prDraft = false
    private var prFeature: Path? = null
    private var prBusy = false
    private var prMode = DeliveryMode.PULL_REQUEST
    /** O modo da última entrega enviada: só a de PR usa o título e a descrição. */
    private var prSent = DeliveryMode.PULL_REQUEST

    init {
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { onSnapshot() })
        project.messageBus.connect(this).subscribe(PullRequestService.CHANGED, PullRequestService.Listener { onDelivery() })
        project.messageBus.connect(this).subscribe(PrRadarService.CHANGED, PrRadarService.Listener { refresh() })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ updateTimer(); refresh() }, project.disposed) },
        )
        project.messageBus.connect(this).subscribe(
            TomahawkToolWindowFactory.GOTO,
            TomahawkToolWindowFactory.GotoListener { step ->
                selected = step
                if (step == SddStep.SPECIFY && service.snapshot.active?.spec != null) newFeature = true
                refresh(force = true)
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SpecKitSetup.Listener.TOPIC,
            SpecKitSetup.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        Disposer.register(this) { timer.stop() }
        updateTimer()
        pr.refresh()
        refresh()
    }

    /**
     * Depois de uma entrega sem erro, a mensagem do commit volta ao valor da feature; depois de um PR, os outros
     * campos também, porque ele já está aberto.
     */
    private fun onDelivery() {
        val busy = pr.step != null
        if (prBusy && !busy && pr.lastError == null) {
            if (prSent == DeliveryMode.PULL_REQUEST) {
                prFields.clear()
                prDraft = false
            } else {
                prFields.remove("commit")
            }
        }
        prBusy = busy
        refresh()
    }

    private fun onSnapshot() {
        val active = service.snapshot.active?.dir
        if (newFeature && active != null && active != lastActive && lastActive != null) newFeature = false
        lastActive = active
        refresh()
    }

    private fun updateTimer() {
        if (runner.progress != null) timer.start() else timer.stop()
    }

    override fun body(): String {
        val s = service.snapshot
        if (s.layout == null) return WebUi.header("🚀", "Lançador", "Lendo o Spec Kit…", "Procurando .specify/ e specs/ no projeto.")
        val ready = TomahawkSettings.ready
        if (!s.initialized) return uninitialized(ready)
        val feature = if (newFeature) null else s.active
        val statuses = s.statuses(feature)
        val next = statuses.firstOrNull { it.next }
        val done = statuses.count { it.state == StepState.DONE }
        val current = selected?.takeIf { st -> statuses.any { it.step == st } } ?: next?.step ?: SddStep.CONSTITUTION
        return buildString {
            val tasks = feature?.tasks
            val chips = buildString {
                if (tasks != null) append(WebUi.chip("Tarefas", "${tasks.done}/${tasks.total}", if (tasks.total > 0 && tasks.done == tasks.total) "green" else null))
                feature?.pending?.size?.takeIf { it > 0 }?.let { append(WebUi.chip("Pendências", "$it", "yellow")) }
                append(aiChip(ready))
                s.integration.key?.let { append(WebUi.chip("Integração", WebUi.attr(it))) }
            }
            val subtitle = when {
                next != null -> "Próxima etapa: <b>${esc(next.step.codename)}</b> <span class=\"st-cmd\">${esc(s.invocation(next.step))}</span>"
                statuses.any { it.step == SddStep.CONVERGE && it.state == StepState.DONE } -> "🎯 Missão cumprida: a implementação convergiu."
                else -> "Nenhuma etapa pendente."
            }
            append(WebUi.header("🚀", "Missão SDD · Spec Kit", esc(feature?.name ?: "Nova feature"), subtitle, chips,
                WebUi.ring(done, statuses.size, "green", "$done/${statuses.size}") + "<small>etapas concluídas</small>"))
            if (!ready) append(aiNotice())
            if (!s.constitutionReady && current != SddStep.CONSTITUTION) {
                append("<div class=\"notice info\"><span class=\"ni\">📜</span><div class=\"nb\">A constituição ainda é o modelo. Comece pelas <b>Regras de Engajamento</b> para guiar as outras etapas.")
                append("<div>").append(WebUi.button("Ir para Regras de Engajamento", "step", arg = SddStep.CONSTITUTION.name)).append("</div></div></div>")
            }
            runner.progress?.let { append(Flight.card(it)) }
            append(WebUi.section("Features"))
            append(features(s, feature))
            append(WebUi.section("Plano de missão", "<span class=\"tags\">" + legend() + "</span>"))
            append("<div class=\"stepper\">")
            statuses.forEach { append(step(it, it.step == current, s, feature, ready)) }
            append("</div>")
            append(delivery(s, statuses))
        }
    }

    /** A Extração: commit, push e pull request da feature ativa, quando o projeto está num repositório do GitHub. */
    private fun delivery(s: Snapshot, statuses: List<StepStatus>): String = buildString {
        if (pr.gateway == null) return@buildString
        val state = pr.state
        val local = state?.local
        if (state != null && local == null) return@buildString
        val feature = s.active
        if (feature?.dir != prFeature) {
            prFields.clear()
            prFeature = feature?.dir
        }
        append(WebUi.section("Extração · Pull Request", WebUi.button("↻ Atualizar", "pr-refresh", "ghost", title = "Relê o repositório e o GitHub")))
        append("<div class=\"card2\" data-form=\"pr\">")
        append("<h3>🛬 Leve a missão para revisão</h3>")
        append("<div class=\"desc\">Só o commit, o commit com o push da branch ou tudo isso e o pull request no GitHub. A mensagem do commit e o texto do PR saem da spec e das tarefas, ou a IA escreve a partir do diff.</div>")
        if (local == null) {
            append("<div class=\"status-line\">Lendo o repositório Git…</div></div>")
            return@buildString
        }
        val remote = state.remote
        val open = remote?.openPr
        append("<div class=\"chips\" style=\"margin:0 0 10px\">")
        append(WebUi.chip("Repositório", esc(local.repo)))
        append(WebUi.chip("Branch", esc(local.branch ?: "sem branch")))
        append(WebUi.chip("Alterações", "${local.changes.size}", if (local.changes.isNotEmpty()) "yellow" else null))
        append(WebUi.chip("Sem push", "${local.ahead} commit(s)", if (local.ahead > 0) "blue" else null))
        append(if (local.account != null) WebUi.chip("GitHub", "@" + esc(local.account), "green") else WebUi.chip("GitHub", "entrar", "yellow", cmd = "pr-login"))
        append("</div>")

        val mode = prMode
        val opensPr = mode == DeliveryMode.PULL_REQUEST
        val canPr = local.account != null && remote != null
        if (local.account == null) {
            append(notice("warn", "🔑", "Para abrir pull requests, entre na conta do GitHub da IDE em <b>${esc(local.host)}</b>. Commitar e enviar já funcionam.",
                WebUi.button("🔑 Entrar no GitHub", "pr-login")))
        } else {
            state.problem?.let { append(notice("err", "⚠️", "O GitHub não respondeu: ${esc(it)}", WebUi.button("Tentar de novo", "pr-refresh"))) }
            if (remote == null && pr.loading) append("<div class=\"status-line\" style=\"margin:0 0 10px\">Consultando o GitHub…</div>")
        }
        val busy = pr.step
        val writing = pr.writing
        if (busy != null) append(notice("info", "🛰️", esc(busy)))
        if (writing != null) append(notice("info", "✨", "A IA está escrevendo ${esc(writing.label)}…"))
        pr.lastError?.takeIf { busy == null && writing == null }?.let { append(notice("err", "⚠️", esc(it))) }
        if (open != null) {
            val badge = if (open.draft) " <span class=\"tag\">rascunho</span>" else ""
            val waiting = PrRadarService.getInstance(project).pendingCount(open.number)
            val radio = if (waiting > 0) " · 💬 <b>$waiting conversa(s)</b> aguardando sua resposta" else ""
            append(notice("ok", "✅", "<b>PR #${open.number}</b> aberto: ${esc(open.title)} → <code>${esc(open.base)}</code>$badge$radio",
                WebUi.button("🌐 Abrir no GitHub", "pr-open", arg = open.url) + " " +
                    WebUi.button(if (waiting > 0) "📻 Ver os comentários ($waiting)" else "📻 Ver os comentários", "radio", if (waiting > 0) "primary" else "")))
        }
        if (opensPr && open == null && statuses.none { it.step == SddStep.CONVERGE && it.state == StepState.DONE }) {
            append("<div class=\"status-line\" style=\"margin:0 0 10px\">A Avaliação de Danos ainda não convergiu: dá para abrir o PR já, como rascunho, e completar depois.</div>")
        }

        val specTitle = feature?.spec?.file?.let { SpecKitLayout.read(it)?.lines() }?.let(PullRequestDraft::specTitle)
        val branch = prFields["branch"] ?: PullRequestDraft.branch(feature, local.branch, remote?.defaultBranch)
        val base = prFields["base"] ?: remote?.let { PullRequestDraft.base(it, branch) }.orEmpty()
        val title = prFields["title"] ?: PullRequestDraft.title(feature, specTitle)
        val commit = prFields["commit"] ?: title
        val text = prFields["body"] ?: PullRequestDraft.body(feature, specTitle) { s.relative(it) }
        val aiWhy = when {
            busy != null -> "Há uma entrega em andamento"
            writing != null -> "A IA já está escrevendo"
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            else -> null
        }

        append("<div class=\"field\"><label>O que fazer</label>")
        append(WebUi.seg("mode", DeliveryMode.entries.map { it.name to modeLabel(it) }, mode.name, send = "pr-mode")).append("</div>")
        append("<div class=\"cols\">")
        append(input("Branch", "branch", branch, if (branch != local.branch && branch.isNotEmpty()) "Criada agora, a partir de ${esc(local.branch ?: "HEAD")}." else null))
        if (opensPr && open == null && remote != null) {
            val bases = (listOf(remote.defaultBranch, "develop") + remote.branches.sorted()).distinct().filter { it in remote.branches && it != branch }
            append("<div class=\"field\"><label>Base do PR</label><select class=\"in\" name=\"base\" data-change=\"pr-base\">")
            bases.forEach { append("<option value=\"${WebUi.attr(it)}\"").append(if (it == base) " selected" else "").append(">${esc(it)}</option>") }
            append("</select></div>")
        }
        append("</div>")
        if (local.changes.isNotEmpty()) {
            append("<div class=\"field\"><div class=\"fh\"><label>Mensagem do commit</label>")
            append(aiButton(WriteTarget.COMMIT, writing, aiWhy)).append("</div>")
            append("<textarea class=\"in\" name=\"commit\" data-draft=\"pr.commit\" style=\"min-height:110px\">").append(esc(commit)).append("</textarea>")
            append("<span class=\"hint\">A primeira linha é o título do commit; depois de uma linha em branco, o corpo com os detalhes.</span></div>")
            append("<details style=\"margin:-6px 0 12px\"><summary class=\"status-line\" style=\"cursor:pointer\">${local.changes.size} arquivo(s) no commit · fora tomahawk/ e .idea/</summary><div class=\"list\" style=\"margin-top:6px\">")
            local.changes.take(MAX_FILES).forEach { append("<div class=\"li\"><span class=\"ac-file\">").append(esc(it)).append("</span></div>") }
            if (local.changes.size > MAX_FILES) append("<div class=\"li meta\">e mais ${local.changes.size - MAX_FILES}…</div>")
            append("</div></details>")
        }
        if (opensPr && open == null) {
            append(input("Título do PR", "title", title, null))
            append("<div class=\"field\"><div class=\"fh\"><label>Descrição do PR</label>")
            append(aiButton(WriteTarget.PULL_REQUEST, writing, aiWhy ?: if (base.isEmpty()) "Escolha a base do PR" else null)).append("</div>")
            append("<textarea class=\"in\" name=\"body\" data-draft=\"pr.body\" style=\"min-height:160px\">").append(esc(text)).append("</textarea>")
            append("<span class=\"hint\">✨ Gerar com IA escreve o título e a descrição a partir dos commits e do diff contra a base.</span></div>")
        }

        append("<div class=\"st-tools\"><div class=\"row-btns\">")
        if (opensPr && open == null) {
            append("<label class=\"status-line\" style=\"margin:0;display:inline-flex;gap:6px;align-items:center\"><input type=\"checkbox\" name=\"draft\" data-change=\"pr-draft\"")
            append(if (prDraft) " checked" else "").append("> Abrir como rascunho</label>")
        }
        append("</div><div class=\"row-btns\">")
        val changes = local.changes.isNotEmpty()
        val label = when (mode) {
            DeliveryMode.COMMIT -> "💾 Commitar ${local.changes.size} arquivo(s)"
            DeliveryMode.PUSH -> if (changes) "⬆️ Commitar e enviar" else "⬆️ Enviar ${local.ahead} commit(s)"
            DeliveryMode.PULL_REQUEST -> when {
                open != null && changes -> "⬆️ Commitar e enviar ao PR #${open.number}"
                open != null -> "⬆️ Enviar ao PR #${open.number}"
                changes -> "🚀 Commitar, enviar e abrir PR"
                else -> "🚀 Enviar e abrir PR"
            }
        }
        val nothingToPush = !changes && local.ahead == 0 && local.upstream != null && branch == local.branch
        val why = when {
            busy != null -> "Há uma entrega em andamento"
            writing != null -> "A IA está escrevendo o texto"
            mode == DeliveryMode.COMMIT && !changes -> "Não há alterações para commitar"
            mode == DeliveryMode.PUSH && nothingToPush -> "Não há alterações nem commits sem push"
            opensPr && !canPr -> if (local.account == null) "Entre na conta do GitHub para abrir o PR" else "O GitHub não respondeu"
            opensPr && open != null && nothingToPush -> "O PR já tem tudo: não há alterações nem commits sem push"
            else -> null
        }
        append(WebUi.button(label, "pr", "primary big", enabled = why == null, title = why))
        append("</div></div>")
        append("<div class=\"status-line\">").append(plan(mode, local.changes.size, branch, local.branch, local.remote, base, open?.number)).append("</div>")
        append("</div>")
    }

    private fun modeLabel(mode: DeliveryMode) = when (mode) {
        DeliveryMode.COMMIT -> "💾 Só commitar"
        DeliveryMode.PUSH -> "⬆️ Commitar e enviar"
        DeliveryMode.PULL_REQUEST -> "🚀 Commitar, enviar e abrir PR"
    }

    private fun aiButton(target: WriteTarget, writing: WriteTarget?, why: String?): String =
        WebUi.button(
            if (writing == target) "⏳ Gerando…" else "✨ Gerar com IA",
            "pr-write",
            "ghost sm",
            arg = target.name,
            enabled = why == null,
            title = why ?: "A IA do Arsenal (${TomahawkSettings.provider.title}) escreve ${target.label} a partir das alterações",
        )

    private fun plan(mode: DeliveryMode, changes: Int, branch: String, current: String?, remote: String, base: String, open: Int?): String {
        val steps = buildList {
            if (branch.isNotEmpty() && branch != current) add("cria a branch <b>${esc(branch)}</b>")
            if (changes > 0) add("commita $changes arquivo(s)")
            if (mode != DeliveryMode.COMMIT) add("envia <b>${esc(branch)}</b> para <b>${esc(remote)}</b>")
            if (mode == DeliveryMode.PULL_REQUEST) add(if (open != null) "atualiza o PR #$open" else "abre o PR para <b>${esc(base.ifEmpty { "a base" })}</b>")
        }
        if (steps.isEmpty()) return "Nada a fazer."
        return steps.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun input(label: String, name: String, value: String, hint: String?): String = buildString {
        append("<div class=\"field\"><label>").append(label).append("</label>")
        append("<input class=\"in\" name=\"$name\" data-draft=\"pr.$name\" value=\"").append(WebUi.attr(value)).append("\">")
        hint?.let { append("<span class=\"hint\">").append(it).append("</span>") }
        append("</div>")
    }

    /** Guarda o que está nos campos do PR, para o redesenho não apagar. */
    private fun saveFields(data: JsonObject) {
        listOf("branch", "base", "title", "body", "commit").forEach { key -> data.str(key)?.let { prFields[key] = it } }
        data.get("draft")?.let { prDraft = data.str("draft") == "true" }
    }

    private fun notice(kind: String, icon: String, html: String, action: String = ""): String =
        "<div class=\"notice $kind\" style=\"margin:0 0 10px\"><span class=\"ni\">$icon</span><div class=\"nb\">$html" +
            (if (action.isNotEmpty()) "<div>$action</div>" else "") + "</div></div>"

    private fun aiChip(ready: Boolean) =
        if (ready) WebUi.chip("IA", "${TomahawkSettings.provider.title} ✓", "green", cmd = "arsenal")
        else WebUi.chip("IA", "testar integração", "yellow", cmd = "arsenal")

    private fun aiNotice(): String {
        val missing = TomahawkSettings.launch() == null
        val text = if (missing) "O ${TomahawkSettings.provider.title} CLI não foi encontrado. Configure a IA no Arsenal."
        else "Teste a integração com a IA no Arsenal: as missões só são lançadas depois que o teste passa."
        return "<div class=\"notice warn\"><span class=\"ni\">⚠️</span><div class=\"nb\">$text<div>${WebUi.button("🛠️ Abrir o Arsenal", "arsenal")}</div></div></div>"
    }

    private fun legend() = listOf(StepState.DONE to "green", StepState.READY to "orange", StepState.PARTIAL to "blue", StepState.OPTIONAL to "gray")
        .joinToString("") { (state, tone) -> "<span class=\"b t-$tone\">${state.label}</span>" }

    private fun uninitialized(ready: Boolean): String = buildString {
        val specify = TomahawkSettings.resolveSpecify()
        append(WebUi.header("🚀", "Lançador", "Spec Kit não inicializado", "O Tomahawk dispara os comandos /speckit pelo ${esc(TomahawkSettings.provider.title)} CLI.", aiChip(ready)))
        if (!ready) append(aiNotice())
        append("<div class=\"empty2\"><div class=\"big\">🛰️</div><h3>Prepare a base de lançamento</h3>")
        append("<p>Este projeto ainda não tem o <b>.specify/</b>. Inicialize o Spec Kit para instalar os comandos de SDD no agente escolhido (")
        append(esc(TomahawkSettings.provider.integration)).append(").</p>")
        append("<div class=\"cmdline\">specify init --here --integration ").append(esc(TomahawkSettings.provider.integration)).append("</div><div>")
        append(
            WebUi.button(
                "⚡ Inicializar Spec Kit", "init", "primary big", enabled = specify != null && SpecKitSetup.job == null,
                title = if (specify == null) "Instale o Spec Kit nesta máquina primeiro" else "Roda specify init --here no terminal da IDE",
            ),
        )
        append("</div>")
        append("<div style=\"text-align:left;max-width:640px;margin:0 auto\">").append(SpecKitWeb.machine(specify)).append("</div>")
        append("</div>")
    }

    private fun features(s: Snapshot, chosen: FeatureReport?): String = buildString {
        append("<div class=\"feats\">")
        s.features.reversed().forEach { f ->
            val on = !newFeature && f.dir == chosen?.dir
            val number = f.name.substringBefore('-').takeIf { it.all(Char::isDigit) }
            val label = if (number != null) f.name.substringAfter('-') else f.name
            val status = when {
                f.tasks != null -> "${f.tasks.done}/${f.tasks.total} tarefas"
                f.plan != null -> "plano pronto"
                f.spec != null -> "spec pronta"
                else -> "vazia"
            }
            append("<div class=\"feat").append(if (on) " on" else "").append("\" data-cmd=\"feature\" data-arg=\"").append(WebUi.attr(f.dir.toString())).append("\">")
            append("<div class=\"fn\">").append(number?.let { "<span class=\"num\">#$it</span> " }.orEmpty()).append(esc(label.replace('-', ' '))).append("</div>")
            append("<div class=\"fm\">").append(status).append(if (f.dir == s.active?.dir) " · <b>ativa</b>" else "").append("</div>")
            val tasks = f.tasks
            append(com.mio.tomahawk.view.ArtifactHtml.bar(tasks?.done ?: if (f.plan != null) 2 else if (f.spec != null) 1 else 0, tasks?.total ?: 3, "green", "gray"))
            append("</div>")
        }
        append("<div class=\"feat new").append(if (newFeature) " on" else "").append("\" data-cmd=\"feature\" data-arg=\"NEW\">＋ Nova feature</div>")
        append("</div>")
    }

    private fun step(status: StepStatus, open: Boolean, s: Snapshot, feature: FeatureReport?, ready: Boolean): String = buildString {
        val step = status.step
        val state = status.state.name.lowercase()
        val tone = tone(status.state)
        append("<div class=\"st $state").append(if (status.next) " next" else "").append(if (open) " sel" else "").append("\">")
        append("<div class=\"st-node\">").append(
            when (status.state) {
                StepState.DONE -> "✓"
                StepState.UNAVAILABLE -> "!"
                else -> step.number.toString()
            },
        ).append("</div><div class=\"st-card\">")
        append("<div class=\"st-head\" data-cmd=\"step\" data-arg=\"${step.name}\"><span class=\"st-name\">").append(esc(step.codename)).append("</span>")
        append("<span class=\"st-cmd\">").append(esc(s.invocation(step))).append("</span><span class=\"grow\"></span><span class=\"tags\">")
        if (status.next) append("<span class=\"tag t-next\">Próxima</span>")
        if (step.optional) append("<span class=\"tag\">opcional</span>")
        if (step.readOnly) append("<span class=\"tag\">só leitura</span>")
        if (step.interactive) append("<span class=\"tag\">faz perguntas</span>")
        append("</span><span class=\"b t-$tone\">").append(status.state.label).append("</span></div>")
        append("<div class=\"st-reason\">").append(esc(status.reason)).append("</div>")
        if (open) append(form(status, s, feature, ready))
        append("</div></div>")
    }

    private fun form(status: StepStatus, s: Snapshot, feature: FeatureReport?, ready: Boolean): String = buildString {
        val step = status.step
        val blocked = status.state == StepState.BLOCKED || status.state == StepState.UNAVAILABLE
        val flying = runner.progress != null
        val mode = modes[step] ?: if (step.interactive && LaunchTerminal.available() != null) RunMode.TERMINAL else TomahawkSettings.runMode.takeIf { it != RunMode.ASK } ?: RunMode.BACKGROUND
        append("<div class=\"st-body\" data-form=\"launch\">")
        append("<p class=\"st-purpose\">").append(esc(step.purpose)).append("</p>")
        s.commands[step]?.let { ref ->
            append("<div class=\"chips\" style=\"margin:0 0 10px\">")
            append(WebUi.chip(if (ref.skill) "Skill" else "Comando", esc(ref.file.parent?.fileName?.toString()?.takeIf { ref.skill } ?: ref.file.fileName.toString()), cmd = "open", arg = ref.file.toString()))
            feature?.let { append(WebUi.chip("Feature", esc(it.name))) }
            append("</div>")
        }
        if (blocked) append("<div class=\"notice warn\" style=\"margin:0 0 10px\"><span class=\"ni\">🔒</span><div class=\"nb\">").append(esc(status.reason)).append("</div></div>")
        append("<div class=\"field\"><label>Entrada · \$ARGUMENTS").append(if (step.argsRequired) " (obrigatória)" else " (opcional)").append("</label>")
        append("<textarea class=\"in\" name=\"text\" data-draft=\"${step.name}\" placeholder=\"").append(WebUi.attr(step.argsHint)).append("\">")
        append(esc(drafts[step].orEmpty())).append("</textarea></div>")
        append("<div class=\"st-tools\"><div class=\"row-btns\">")
        append(WebUi.seg("mode", listOf(RunMode.BACKGROUND.name to "🛰️ Na aba, monitorada", RunMode.TERMINAL.name to "💻 No terminal"), mode.name, send = "mode"))
        append("</div><div class=\"row-btns\">")
        val artifact = artifactOf(step, feature)
        if (artifact != null) append(WebUi.button("📄 Abrir artefato", "artifact", "ghost", arg = step.name))
        append(WebUi.button("⧉ Copiar comando", "copy", "ghost", arg = step.name, title = "Copia o comando com a entrada, para colar no chat do agente"))
        val why = when {
            flying -> "Há uma missão em voo"
            !ready -> "Teste a integração com a IA no Arsenal"
            blocked -> status.reason
            else -> null
        }
        append(WebUi.button("🚀 Lançar", "launch", "primary big", arg = step.name, enabled = why == null, title = why ?: "Ctrl+Enter"))
        append("</div></div>")
        append("<div class=\"status-line\"><span class=\"kbd\">Ctrl</span> + <span class=\"kbd\">Enter</span> lança · ")
        append(if (mode == RunMode.TERMINAL) "no terminal você conversa com a IA e responde às perguntas." else "na aba a Telemetria acompanha cada passo.")
        append("</div></div>")
    }

    private fun tone(state: StepState) = when (state) {
        StepState.DONE -> "green"
        StepState.PARTIAL -> "blue"
        StepState.READY -> "orange"
        StepState.RECOMMENDED -> "yellow"
        StepState.UNAVAILABLE -> "red"
        else -> "gray"
    }

    private fun artifactOf(step: SddStep, feature: FeatureReport?): Path? = when (step) {
        SddStep.CONSTITUTION -> service.snapshot.layout?.constitution
        SddStep.SPECIFY, SddStep.CLARIFY -> feature?.spec?.file
        SddStep.PLAN -> feature?.plan?.file
        SddStep.CHECKLIST -> feature?.customChecklists?.firstOrNull()?.file ?: feature?.checklists?.firstOrNull()?.file
        SddStep.TASKS, SddStep.IMPLEMENT, SddStep.CONVERGE, SddStep.ANALYZE -> feature?.tasks?.file
    }?.takeIf { Files.exists(it) }

    override fun onMessage(command: String, data: JsonObject) {
        val step = data.str("arg")?.let { name -> SddStep.entries.firstOrNull { it.name == name } }
        when (command) {
            "step" -> {
                selected = step
                refresh(force = true)
            }
            "feature" -> {
                val arg = data.str("arg") ?: return
                if (arg == "NEW") {
                    newFeature = true
                    selected = SddStep.SPECIFY
                } else {
                    newFeature = false
                    selected = null
                    service.activate(Path.of(arg))
                }
                refresh(force = true)
            }
            "draft" -> {
                val key = data.str("arg").orEmpty()
                if (key.startsWith("pr.")) prFields[key.removePrefix("pr.")] = data.str("text").orEmpty()
                else step?.let { drafts[it] = data.str("text").orEmpty() }
            }
            "pr-base" -> data.str("value")?.let { prFields["base"] = it }
            "pr-mode" -> {
                saveFields(data)
                DeliveryMode.entries.firstOrNull { it.name == data.str("value") }?.let { prMode = it }
                refresh(force = true)
            }
            "pr-write" -> {
                val target = WriteTarget.entries.firstOrNull { it.name == data.str("arg") } ?: return
                saveFields(data)
                val base = data.str("base") ?: pr.state?.remote?.openPr?.base.orEmpty()
                val feature = service.snapshot.active?.let { service.snapshot.relative(it.dir) }
                pr.write(target, base, feature) { text ->
                    when (target) {
                        WriteTarget.COMMIT -> prFields["commit"] = text
                        WriteTarget.PULL_REQUEST -> {
                            val (title, body) = DeliveryWriter.pullRequest(text)
                            if (title.isNotBlank()) prFields["title"] = title
                            if (body.isNotBlank()) prFields["body"] = body
                        }
                    }
                    refresh(force = true)
                }
            }
            "pr-draft" -> prDraft = data.str("draft") == "true"
            "pr-refresh" -> pr.refresh(remote = true, delayMs = 0)
            "pr-login" -> pr.login()
            "pr-open" -> data.str("arg")?.let(BrowserUtil::browse)
            "pr" -> {
                saveFields(data)
                prSent = prMode
                pr.deliver(
                    DeliveryRequest(
                        branch = data.str("branch").orEmpty(),
                        base = data.str("base") ?: pr.state?.remote?.openPr?.base.orEmpty(),
                        commitMessage = data.str("commit") ?: prFields["commit"].orEmpty(),
                        title = data.str("title") ?: prFields["title"].orEmpty(),
                        body = data.str("body") ?: prFields["body"].orEmpty(),
                        draft = prDraft,
                        mode = prMode,
                    ),
                )
            }
            "mode" -> {
                val target = selected ?: return
                RunMode.entries.firstOrNull { it.name == data.str("value") }?.let { modes[target] = it }
            }
            "launch" -> {
                step ?: return
                val text = data.str("text").orEmpty()
                drafts[step] = text
                val feature = if (newFeature) null else service.snapshot.active
                val mode = RunMode.entries.firstOrNull { it.name == data.str("mode") }
                val mission = Mission(step, text, feature?.takeIf { step != SddStep.SPECIFY }?.let { service.snapshot.relative(it.dir) }, mode)
                if (runner.launch(mission)) drafts.remove(step)
                refresh(force = true)
            }
            "copy" -> step?.let { runner.copyCommand(Mission(it, data.str("text").orEmpty(), null)) }
            "artifact" -> step?.let { s -> artifactOf(s, if (newFeature) null else service.snapshot.active)?.let { openArtifact(project, it) } }
            "open" -> data.str("arg")?.let { openArtifact(project, Path.of(it)) }
            "init" -> runner.initSpecKit()
            "specify-install" -> SpecKitSetup.install(project)
            "specify-upgrade" -> SpecKitSetup.upgrade(project)
            "specify-recheck" -> {
                SpecKitSetup.recheck()
                service.refresh(0)
            }
            "arsenal" -> TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.ARSENAL)
            "radio" -> TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.RADIO)
            "abort" -> runner.progress?.cancel()
        }
    }

    private companion object {
        const val TICK_MS = 500
        const val MAX_FILES = 40
    }
}
