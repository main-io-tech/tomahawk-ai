package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.mio.tomahawk.base.TomahawkToolWindowFactory
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitLayout
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.openArtifact
import com.mio.tomahawk.ui.openFile
import com.mio.tomahawk.view.ArtifactHtml
import com.mio.tomahawk.view.DataModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A Avaliação na versão web: um cartão por artefato da feature, com os números de cada um, os pontos a esclarecer,
 * o progresso por fase, as próximas tarefas e os checklists. Clicar num artefato abre a Visualização; num item, o
 * texto na linha.
 */
class EvaluationWeb(project: Project) : WebTab(project) {

    override val accent = "purple"

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private var all = false

    init {
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refresh() })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        refresh()
    }

    override fun body(): String {
        val s = service.snapshot
        val layout = s.layout ?: return WebUi.header("🎯", "Avaliação", "Lendo o Spec Kit…", null)
        if (!s.initialized) {
            return WebUi.header("🎯", "Avaliação", "Nada a avaliar ainda", "Inicialize o Spec Kit no Lançador para começar a missão.") +
                "<div class=\"empty2\"><div class=\"big\">🔭</div><h3>Sem artefatos</h3><p>Os artefatos do Spec Kit aparecem aqui conforme as etapas são lançadas.</p>" +
                WebUi.button("🚀 Ir para o Lançador", "goto", "primary", arg = SddStep.CONSTITUTION.name) + "</div>"
        }
        val active = s.active
        return buildString {
            append(top(s, active))
            runner.progress?.let { append(Flight.card(it)) }
            append(WebUi.section("Artefatos", "<span class=\"grow\"></span>" + WebUi.seg("scope", listOf("active" to "Feature ativa", "all" to "Todas"), if (all) "all" else "active", send = "scope")))
            if (all) {
                append(allFeatures(s))
            } else if (active == null) {
                append(artifactCards(layout, s, null))
                append("<div class=\"empty2\"><div class=\"big\">🎯</div><h3>Nenhuma feature designada</h3><p>Descreva a primeira feature no Lançador (Designar Alvo).</p>")
                append(WebUi.button("🚀 Designar Alvo", "goto", "primary", arg = SddStep.SPECIFY.name)).append("</div>")
            } else {
                append(artifactCards(layout, s, active))
                append(details(active))
            }
        }
    }

    private fun top(s: Snapshot, active: FeatureReport?): String {
        val produced = listOf(s.constitutionReady, active?.spec != null, active?.plan != null, active?.tasks != null, active?.designDocs?.isNotEmpty() == true)
        val chips = buildString {
            active?.spec?.let {
                append(WebUi.chip("Histórias", "${it.stories.size}"))
                append(WebUi.chip("Requisitos", "${it.requirements}"))
            }
            active?.tasks?.let { append(WebUi.chip("Tarefas", "${it.done}/${it.total}", if (it.total > 0 && it.done == it.total) "green" else null)) }
            active?.pending?.size?.takeIf { it > 0 }?.let { append(WebUi.chip("A esclarecer", "$it", "yellow")) }
            val open = active?.customChecklists?.sumOf { it.items.size - it.done } ?: 0
            if (open > 0) append(WebUi.chip("Checklist", "$open abertos", "orange"))
        }
        val actions = buildString {
            append("<div class=\"row-btns\" style=\"margin-top:10px\">")
            val canRun = active?.tasks != null && runner.progress == null && TomahawkSettings.ready
            append(WebUi.button("🧪 Simulação de Rota", "run", arg = SddStep.ANALYZE.name, enabled = canRun && SddStep.ANALYZE in s.commands,
                title = "${s.invocation(SddStep.ANALYZE)}: consistência entre spec, plano e tarefas"))
            append(WebUi.button("💥 Avaliação de Danos", "run", arg = SddStep.CONVERGE.name, enabled = canRun && SddStep.CONVERGE in s.commands,
                title = "${s.invocation(SddStep.CONVERGE)}: confere a implementação contra os artefatos"))
            append("</div>")
        }
        return WebUi.header("🎯", "Avaliação de artefatos", esc(active?.name ?: "Nenhuma feature ativa"),
            "O que o Spec Kit gerou, para revisar antes de seguir.", chips + actions,
            WebUi.ring(produced.count { it }, produced.size, "purple", "${produced.count { it }}/${produced.size}") + "<small>artefatos</small>")
    }

    private fun artifactCards(layout: SpecKitLayout, s: Snapshot, f: FeatureReport?): String = buildString {
        append("<div class=\"grid2\">")
        append(card("📜", "Constituição", "constitution.md", "purple", layout.constitution.takeIf { Files.exists(it) },
            if (s.constitutionReady) "<span class=\"b t-green\">definida</span>" else "<span class=\"b t-yellow\">ainda é o modelo</span>",
            "Os princípios que guiam todas as etapas.", SddStep.CONSTITUTION))
        if (f != null) {
            val spec = f.spec
            append(card("🎯", "Especificação", "spec.md", "orange", spec?.file,
                spec?.let {
                    "<span class=\"ac-big\">${it.stories.size}</span><span class=\"muted\">histórias</span> " +
                        badge("${it.requirements} requisitos", "purple") + badge("${it.successCriteria} critérios", "teal") +
                        (if (it.pending.isNotEmpty()) badge("${it.pending.size} a esclarecer", "yellow") else badge("sem pendências", "green"))
                }.orEmpty(), "O quê e por quê da feature.", SddStep.SPECIFY))
            val plan = f.plan
            append(card("🧭", "Plano de voo", "plan.md", "blue", plan?.file,
                plan?.context?.take(3)?.joinToString("") { (_, v) -> badge(v.take(26), "blue") }.orEmpty().ifEmpty { "<span class=\"muted\">plano gerado</span>" },
                "Stack, arquitetura e estrutura.", SddStep.PLAN))
            val dataModel = f.dir.resolve("data-model.md").takeIf { Files.exists(it) }
            val model = dataModel?.let { runCatching { DataModel.parse(Files.readString(it)) }.getOrNull() }
            append(card("🧩", "Modelo de dados", "data-model.md", "teal", dataModel,
                model?.let { "<span class=\"ac-big\">${it.entities.size}</span><span class=\"muted\">entidades</span> " + badge("${it.relations.size} relacionamentos", "teal") }.orEmpty(),
                "Tabelas, campos e relacionamentos.", if (plan == null) SddStep.PLAN else null))
            val contracts = f.designDocs.filter { "contracts" in it.toString().replace('\\', '/') }
            append(card("🔌", "Contratos", "contracts/", "blue", contracts.firstOrNull(),
                if (contracts.isEmpty()) "" else "<span class=\"ac-big\">${contracts.size}</span><span class=\"muted\">arquivo(s)</span>",
                "APIs e interfaces externas.", null))
            val research = f.dir.resolve("research.md").takeIf { Files.exists(it) }
            append(card("🔬", "Pesquisa", "research.md", "teal", research, "", "Decisões técnicas e alternativas.", null))
            val quickstart = f.dir.resolve("quickstart.md").takeIf { Files.exists(it) }
            append(card("⚡", "Guia rápido", "quickstart.md", "yellow", quickstart, "", "Como validar a feature de ponta a ponta.", null))
            val tasks = f.tasks
            append(card("🛰️", "Waypoints", "tasks.md", "green", tasks?.file,
                tasks?.let {
                    "<span class=\"ac-big\">${if (it.total == 0) 0 else it.done * 100 / it.total}%</span><span class=\"muted\">${it.done}/${it.total} tarefas</span>" +
                        ArtifactHtml.bar(it.done, it.total, "green", "orange")
                }.orEmpty(), "Tarefas por fase, em ordem de dependência.", SddStep.TASKS))
            val lists = f.checklists
            val open = lists.sumOf { it.items.size - it.done }
            append(card("✅", "Checklists", "checklists/", "pink", lists.firstOrNull()?.file,
                if (lists.isEmpty()) "" else "<span class=\"ac-big\">${lists.size}</span><span class=\"muted\">lista(s)</span> " +
                    (if (open > 0) badge("$open itens abertos", "pink") else badge("revisados", "green")),
                "A qualidade dos requisitos.", SddStep.CHECKLIST))
        }
        append("</div>")
    }

    private fun badge(text: String, tone: String) = "<span class=\"b t-$tone\">${esc(text)}</span>"

    private fun card(icon: String, name: String, file: String, tone: String, path: Path?, metrics: String, foot: String, launch: SddStep?): String = buildString {
        val missing = path == null
        append("<div class=\"acard").append(if (missing) " missing" else "").append("\" style=\"--t:var(--$tone);--t-fg:var(--$tone-fg)\"")
        if (!missing) append(" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(path.toString())).append("\" title=\"Abrir formatado\"")
        append("><div class=\"ac-top\"><span class=\"ac-icon\">$icon</span><span class=\"ac-name\">").append(esc(name)).append("</span>")
        append("<span class=\"ac-file\">").append(esc(file)).append("</span></div>")
        if (missing) {
            append("<div class=\"ac-foot\">Ainda não gerado. ").append(esc(foot)).append("</div>")
            launch?.let { append("<div>").append(WebUi.button("🚀 Lançar ${it.codename}", "goto", arg = it.name)).append("</div>") }
        } else {
            if (metrics.isNotEmpty()) append("<div class=\"ac-metrics\">").append(metrics).append("</div>")
            append("<div class=\"ac-foot\">").append(esc(foot)).append("</div>")
        }
        append("</div>")
    }

    private fun details(f: FeatureReport): String = buildString {
        if (f.pending.isNotEmpty()) {
            append(WebUi.section("Pontos a esclarecer", "<span class=\"grow\"></span>" + WebUi.button("❓ Reconhecimento", "goto", arg = SddStep.CLARIFY.name)))
            append("<div class=\"list\">")
            f.pending.forEach { m ->
                append("<div class=\"li\" data-cmd=\"line\" data-arg=\"").append(WebUi.attr(m.file.toString())).append("\" data-line=\"${m.line}\">")
                append("<span class=\"b t-yellow\">⚠</span><div class=\"grow\"><div class=\"ttl\">")
                append(ArtifactHtml.decorate(ArtifactHtml.inline(m.text.trimStart('-', '*', ' ')))).append("</div>")
                append("<div class=\"meta\">").append(esc(m.file.name)).append(" · linha ${m.line + 1}</div></div></div>")
            }
            append("</div>")
        }
        val tasks = f.tasks
        if (tasks != null && tasks.phases.isNotEmpty()) {
            append(WebUi.section("Progresso por fase"))
            append("<div class=\"list\">")
            tasks.phases.forEach { phase ->
                val complete = phase.done == phase.tasks.size
                append("<div class=\"li\" data-cmd=\"line\" data-arg=\"").append(WebUi.attr(tasks.file.toString())).append("\" data-line=\"${phase.line}\">")
                append("<span>").append(if (complete) "✅" else if (phase.done > 0) "🔵" else "⚪").append("</span><div class=\"grow\"><div class=\"ttl\">")
                append(ArtifactHtml.decorate(ArtifactHtml.inline(phase.title))).append("</div>")
                append("<div class=\"meta\">${phase.done} de ${phase.tasks.size} tarefas · ${phase.tasks.count { it.parallel }} paralelizáveis</div></div>")
                append(ArtifactHtml.bar(phase.done, phase.tasks.size, "green", "orange")).append("</div>")
            }
            append("</div>")
            val open = tasks.all.filter { !it.done }
            if (open.isNotEmpty()) {
                append(WebUi.section("Próximas tarefas · ${open.size} abertas", "<span class=\"grow\"></span>" + WebUi.button("🚀 Lançamento", "goto", arg = SddStep.IMPLEMENT.name)))
                append("<div class=\"list\">")
                open.take(MAX_TASKS).forEach { t ->
                    append("<div class=\"li\" data-cmd=\"line\" data-arg=\"").append(WebUi.attr(tasks.file.toString())).append("\" data-line=\"${t.line}\">")
                    append("<span class=\"st-cmd\">").append(esc(t.id ?: "•")).append("</span><div class=\"grow\"><div class=\"ttl\">")
                    val text = t.text.removePrefix(t.id.orEmpty()).replace("[P]", "").replace(Regex("""\[US\d+]"""), "").trim()
                    append(ArtifactHtml.decorate(ArtifactHtml.inline(text))).append("</div></div>")
                    if (t.parallel) append("<span class=\"b par\">∥ P</span>")
                    t.story?.let { append(ArtifactHtml.decorate(esc("[$it]"))) }
                    append("</div>")
                }
                if (open.size > MAX_TASKS) append("<div class=\"li\" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(tasks.file.toString())).append("\"><span class=\"meta\">+ ${open.size - MAX_TASKS} tarefas: abrir o tasks.md</span></div>")
                append("</div>")
            }
        }
        if (f.checklists.isNotEmpty()) {
            append(WebUi.section("Checklists"))
            append("<div class=\"list\">")
            f.checklists.forEach { list ->
                append("<div class=\"li\" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(list.file.toString())).append("\">")
                append("<span>✅</span><div class=\"grow\"><div class=\"ttl\">").append(esc(list.file.name)).append(if (list.builtIn) " <span class=\"tag\">da spec</span>" else "").append("</div>")
                append("<div class=\"meta\">${list.done} de ${list.items.size} revisados</div></div>")
                append(ArtifactHtml.bar(list.done, list.items.size, "green", "pink")).append("</div>")
            }
            append("</div>")
        }
    }

    private fun allFeatures(s: Snapshot): String = buildString {
        if (s.features.isEmpty()) {
            append("<div class=\"empty2\"><div class=\"big\">🗂️</div><h3>Nenhuma feature em specs/</h3></div>")
            return@buildString
        }
        append("<div class=\"list\">")
        s.features.reversed().forEach { f ->
            val tasks = f.tasks
            append("<div class=\"li\" data-cmd=\"activate\" data-arg=\"").append(WebUi.attr(f.dir.toString())).append("\" title=\"Tornar ativa\">")
            append("<span>").append(if (f.dir == s.active?.dir) "🎯" else "📁").append("</span><div class=\"grow\"><div class=\"ttl\">").append(esc(f.name))
            if (f.dir == s.active?.dir) append(" <span class=\"tag t-next\">ativa</span>")
            append("</div><div class=\"meta\">")
            append(listOf("spec" to (f.spec != null), "plano" to (f.plan != null), "tarefas" to (tasks != null), "checklists" to f.checklists.isNotEmpty())
                .joinToString(" · ") { (n, ok) -> (if (ok) "✓ " else "○ ") + n })
            append("</div></div>")
            append(ArtifactHtml.bar(tasks?.done ?: 0, tasks?.total ?: 1, "green", "gray")).append("</div>")
        }
        append("</div>")
    }

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "open" -> data.str("arg")?.let { openArtifact(project, Path.of(it)) }
            "line" -> data.str("arg")?.let { openFile(project, Path.of(it), data.int("line") ?: 0) }
            "goto" -> {
                val step = SddStep.entries.firstOrNull { it.name == data.str("arg") } ?: return
                project.messageBus.syncPublisher(TomahawkToolWindowFactory.GOTO).goto(step)
                TomahawkToolWindowFactory.showTab(project, TomahawkToolWindowFactory.LAUNCHER)
            }
            "run" -> {
                val step = SddStep.entries.firstOrNull { it.name == data.str("arg") } ?: return
                val active = service.snapshot.active ?: return
                runner.launch(Mission(step, "", service.snapshot.relative(active.dir), RunMode.BACKGROUND))
            }
            "scope" -> {
                all = data.str("value") == "all"
                refresh(force = true)
            }
            "activate" -> data.str("arg")?.let {
                all = false
                service.activate(Path.of(it))
            }
            "abort" -> runner.progress?.cancel()
        }
    }

    private companion object {
        const val MAX_TASKS = 8
    }
}
