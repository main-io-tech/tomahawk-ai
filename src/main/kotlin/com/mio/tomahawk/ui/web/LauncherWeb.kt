package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
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

    init {
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { onSnapshot() })
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
        Disposer.register(this) { timer.stop() }
        updateTimer()
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
        }
    }

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
        append(WebUi.button("⚡ Inicializar Spec Kit", "init", "primary big"))
        append("</div>")
        if (specify == null) append("<div class=\"notice warn\"><span class=\"ni\">⚠️</span><div class=\"nb\">O <b>specify</b> não foi encontrado. Instale com <code>uv tool install specify-cli</code>.</div></div>")
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
            "draft" -> step?.let { drafts[it] = data.str("text").orEmpty() }
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
            "arsenal" -> TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.ARSENAL)
            "abort" -> runner.progress?.cancel()
        }
    }

    private companion object {
        const val TICK_MS = 500
    }
}
