package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.tree.TreeUtil
import com.mio.tomahawk.base.MechanismTree
import com.mio.tomahawk.base.RichTreeCellRenderer
import com.mio.tomahawk.base.Segment
import com.mio.tomahawk.base.Stat
import com.mio.tomahawk.base.SummaryHeader
import com.mio.tomahawk.base.Tone
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.base.refreshAction
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.speckit.CheckItem
import com.mio.tomahawk.speckit.Checklist
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.Marker
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.TaskItem
import com.mio.tomahawk.speckit.TaskPhase
import com.mio.tomahawk.speckit.UserStory
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.io.path.name

/** Um nó da árvore de avaliação; [file] e [line] dizem o que abrir no duplo clique. */
private sealed class EvalNode(val file: Path?, val line: Int = 0) {
    class Constitution(file: Path, val ready: Boolean) : EvalNode(file)
    class Feature(val report: FeatureReport, val active: Boolean) : EvalNode(report.spec?.file ?: report.dir)
    class Spec(val report: FeatureReport) : EvalNode(report.spec?.file)
    class Story(file: Path, val story: UserStory) : EvalNode(file, story.line)
    class Pending(val markers: List<Marker>) : EvalNode(markers.firstOrNull()?.file, markers.firstOrNull()?.line ?: 0)
    class PendingItem(val marker: Marker) : EvalNode(marker.file, marker.line)
    class Plan(val report: FeatureReport) : EvalNode(report.plan?.file)
    class Context(file: Path, val key: String, val value: String) : EvalNode(file)
    class Design(val docs: List<Path>, dir: Path) : EvalNode(docs.firstOrNull() ?: dir)
    class Doc(file: Path, val label: String) : EvalNode(file)
    class Tasks(val report: FeatureReport) : EvalNode(report.tasks?.file)
    class Phase(file: Path, val phase: TaskPhase) : EvalNode(file, phase.line)
    class Task(file: Path, val task: TaskItem) : EvalNode(file, task.line)
    class Checklists(val lists: List<Checklist>) : EvalNode(lists.firstOrNull()?.file)
    class ChecklistFile(val list: Checklist) : EvalNode(list.file)
    class Check(file: Path, val item: CheckItem) : EvalNode(file, item.line)
    class Missing(val label: String, val hint: String) : EvalNode(null)
}

/**
 * A aba Avaliação: o que o Spec Kit gerou em cada feature, para revisar antes de seguir. Mostra a spec (histórias,
 * requisitos, critérios), os pontos `[NEEDS CLARIFICATION]`, o contexto técnico do plano, as tarefas por fase e os
 * checklists; o duplo clique abre o artefato na Visualização, ou o trecho no texto.
 */
class EvaluationPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.avaliacao, Tone.PURPLE)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = MechanismTree(model, TomahawkIcons.avaliacao, Tone.PURPLE).apply {
        cellRenderer = Renderer()
        emptyText.text = "Nada gerado ainda: lance Regras de Engajamento ou Designar Alvo no Lançador"
    }
    private var onlyActive = true

    init {
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(MissionProgressPanel(project, this@EvaluationPanel))
            }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        })
        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.EvaluationToolbar",
            DefaultActionGroup(
                refreshAction { service.refresh(0) },
                object : DumbAwareToggleAction("Só a Feature Ativa", "Mostra só a feature ativa ou todas as de specs/", AllIcons.General.Filter) {
                    override fun isSelected(e: AnActionEvent) = onlyActive
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        onlyActive = state
                        reload(service.snapshot)
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                actions.createExpandAllAction(expander, tree),
                actions.createCollapseAllAction(expander, tree),
                Separator.getInstance(),
                launchAction(SddStep.ANALYZE, AllIcons.Actions.Checked),
                launchAction(SddStep.CONVERGE, TomahawkIcons.avaliacao),
            ),
            true,
        ).apply { targetComponent = this@EvaluationPanel }.component
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { reload(it) })
        reload(service.snapshot)
    }

    override fun dispose() = Unit

    /** Lança na aba, sem entrada, a etapa de avaliação [step] para a feature ativa. */
    private fun launchAction(step: SddStep, icon: javax.swing.Icon) =
        object : DumbAwareAction("${step.codename} (${step.invocation()})", step.purpose, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                val active = service.snapshot.active ?: return
                runner.launch(Mission(step, "", service.snapshot.relative(active.dir), RunMode.BACKGROUND))
            }

            override fun update(e: AnActionEvent) {
                val snapshot = service.snapshot
                e.presentation.text = "${step.codename} (${snapshot.invocation(step)})"
                e.presentation.isEnabled = snapshot.active?.tasks != null && runner.progress == null && step in snapshot.commands
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun openSelected() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? EvalNode ?: return
        node.file?.let { openFile(project, it, node.line) }
    }

    private fun reload(snapshot: Snapshot) {
        val expanded = if (root.childCount == 0) null else expandedKeys()
        root.removeAllChildren()
        val layout = snapshot.layout
        if (layout != null && snapshot.initialized) {
            root.add(DefaultMutableTreeNode(EvalNode.Constitution(layout.constitution, snapshot.constitutionReady)))
            val features = if (onlyActive) listOfNotNull(snapshot.active) else snapshot.features.reversed()
            features.forEach { root.add(featureNode(it, it.dir == snapshot.active?.dir)) }
        }
        model.reload()
        if (expanded == null) TreeUtil.expand(tree, 2) else restore(expanded)
        updateHeader(snapshot)
    }

    /** As árvores são refeitas a cada mudança nos artefatos: os nós abertos são lembrados pelo caminho de rótulos. */
    private fun expandedKeys(): Set<String> = TreeUtil.collectExpandedPaths(tree).mapNotNull(::keyOf).toSet()

    private fun restore(keys: Set<String>) {
        root.preorderEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>().forEach { node ->
            val path = javax.swing.tree.TreePath(node.path)
            if (keyOf(path) in keys) tree.expandPath(path)
        }
    }

    private fun keyOf(path: javax.swing.tree.TreePath): String? {
        val nodes = path.path.drop(1).map { (it as? DefaultMutableTreeNode)?.userObject as? EvalNode ?: return null }
        return nodes.joinToString("/") { node ->
            when (node) {
                is EvalNode.Feature -> "f:${node.report.name}"
                is EvalNode.Phase -> "p:${node.phase.title}"
                is EvalNode.ChecklistFile -> "c:${node.list.file.name}"
                else -> node.javaClass.simpleName
            }
        }.ifEmpty { null }
    }

    private fun featureNode(report: FeatureReport, active: Boolean): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(EvalNode.Feature(report, active))
        val spec = report.spec
        if (spec == null) {
            node.add(DefaultMutableTreeNode(EvalNode.Missing("spec.md", "lance Designar Alvo")))
        } else {
            node.add(DefaultMutableTreeNode(EvalNode.Spec(report)).apply {
                spec.stories.forEach { add(DefaultMutableTreeNode(EvalNode.Story(spec.file, it))) }
            })
        }
        if (report.pending.isNotEmpty()) {
            node.add(DefaultMutableTreeNode(EvalNode.Pending(report.pending)).apply {
                report.pending.forEach { add(DefaultMutableTreeNode(EvalNode.PendingItem(it))) }
            })
        }
        val plan = report.plan
        if (plan == null) {
            if (spec != null) node.add(DefaultMutableTreeNode(EvalNode.Missing("plan.md", "lance Plano de Voo")))
        } else {
            node.add(DefaultMutableTreeNode(EvalNode.Plan(report)).apply {
                plan.context.forEach { (key, value) -> add(DefaultMutableTreeNode(EvalNode.Context(plan.file, key, value))) }
            })
        }
        if (report.designDocs.isNotEmpty()) {
            node.add(DefaultMutableTreeNode(EvalNode.Design(report.designDocs, report.dir)).apply {
                report.designDocs.forEach { add(DefaultMutableTreeNode(EvalNode.Doc(it, report.dir.relativize(it).toString().replace('\\', '/')))) }
            })
        }
        val tasks = report.tasks
        if (tasks == null) {
            if (plan != null) node.add(DefaultMutableTreeNode(EvalNode.Missing("tasks.md", "lance Waypoints")))
        } else {
            node.add(DefaultMutableTreeNode(EvalNode.Tasks(report)).apply {
                tasks.phases.forEach { phase ->
                    add(DefaultMutableTreeNode(EvalNode.Phase(tasks.file, phase)).apply {
                        phase.tasks.forEach { add(DefaultMutableTreeNode(EvalNode.Task(tasks.file, it))) }
                    })
                }
            })
        }
        if (report.checklists.isNotEmpty()) {
            node.add(DefaultMutableTreeNode(EvalNode.Checklists(report.checklists)).apply {
                report.checklists.forEach { list ->
                    add(DefaultMutableTreeNode(EvalNode.ChecklistFile(list)).apply {
                        list.items.forEach { add(DefaultMutableTreeNode(EvalNode.Check(list.file, it))) }
                    })
                }
            })
        }
        return node
    }

    private fun updateHeader(snapshot: Snapshot) {
        val active = snapshot.active
        if (!snapshot.initialized) {
            header.update("Nada a avaliar", Tone.GRAY.accent, "Inicialize o Spec Kit no Lançador.")
            return
        }
        if (active == null) {
            header.update(
                "Nenhuma feature",
                Tone.GRAY.accent,
                if (snapshot.constitutionReady) "Constituição definida. Designe o primeiro alvo no Lançador." else "Comece pelas Regras de Engajamento no Lançador.",
            )
            return
        }
        val tasks = active.tasks
        val openChecks = active.customChecklists.sumOf { it.items.size - it.done }
        val pending = active.pending.size
        val color = when {
            pending > 0 -> Tone.YELLOW
            tasks != null && tasks.total > 0 && tasks.done == tasks.total -> Tone.GREEN
            else -> Tone.PURPLE
        }
        val produced = listOfNotNull(
            "spec".takeIf { active.spec != null },
            "plano".takeIf { active.plan != null },
            "${active.designDocs.size} de design".takeIf { active.designDocs.isNotEmpty() },
            "tarefas".takeIf { tasks != null },
            "${active.checklists.size} checklist(s)".takeIf { active.checklists.isNotEmpty() },
        )
        header.update(
            active.name,
            color.accent,
            if (produced.isEmpty()) "Nada gerado ainda." else "Gerado: " + produced.joinToString(" · "),
            stats = listOf(
                Stat(active.spec?.stories?.size ?: 0, "histórias", Tone.BLUE),
                Stat(active.spec?.requirements ?: 0, "requisitos", Tone.PURPLE),
                Stat(tasks?.done ?: 0, "tarefas feitas", Tone.GREEN),
                Stat((tasks?.total ?: 0) - (tasks?.done ?: 0), "tarefas abertas", Tone.ORANGE),
                Stat(pending, "esclarecimentos pendentes", Tone.YELLOW),
                Stat(openChecks, "itens de checklist abertos", Tone.PINK),
            ),
            bar = if (tasks != null) listOf(Segment(tasks.done, Tone.GREEN.accent), Segment(tasks.total - tasks.done, Tone.ORANGE.accent)) else emptyList(),
            warning = if (pending > 0) "$pending ponto(s) [NEEDS CLARIFICATION]: rode o Reconhecimento (clarify) antes do Plano de Voo." else null,
        )
    }

    private class Renderer : RichTreeCellRenderer() {
        override fun describe(row: Row, value: Any?) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject as? EvalNode) {
                is EvalNode.Constitution -> {
                    row.icon(AllIcons.Nodes.Template)
                    row.text("Constituição", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.pill(if (node.ready) "Definida" else "Modelo", if (node.ready) Tone.GREEN else Tone.YELLOW)
                    row.detail(".specify/memory/constitution.md")
                }
                is EvalNode.Feature -> {
                    row.icon(TomahawkIcons.lancador)
                    row.text(node.report.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (node.active) row.pill("Ativa", Tone.ORANGE, bold = true)
                    node.report.tasks?.let { tasks ->
                        row.bar(listOf(Segment(tasks.done, Tone.GREEN.accent), Segment(tasks.total - tasks.done, Tone.ORANGE.accent)))
                        row.text("${tasks.done}/${tasks.total}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                is EvalNode.Spec -> {
                    val spec = node.report.spec ?: return
                    row.icon(AllIcons.FileTypes.Text)
                    row.text("spec.md")
                    row.pill("${spec.stories.size} histórias", Tone.BLUE)
                    row.pill("${spec.requirements} requisitos", Tone.PURPLE)
                    if (spec.successCriteria > 0) row.pill("${spec.successCriteria} critérios", Tone.TEAL)
                    if (spec.clarifications > 0) row.pill("${spec.clarifications} esclarecidas", Tone.GREEN)
                    if (spec.pending.isNotEmpty()) row.pill("${spec.pending.size} pendentes", Tone.YELLOW, bold = true)
                }
                is EvalNode.Story -> {
                    row.icon(AllIcons.General.User)
                    node.story.priority?.let { row.pill(it, Tone.forKey(it), bold = true) }
                    row.text("US${node.story.number}")
                    row.text(node.story.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is EvalNode.Pending -> {
                    row.icon(AllIcons.General.Warning)
                    row.text("Esclarecimentos pendentes", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.pill(node.markers.size.toString(), Tone.YELLOW, bold = true)
                    row.detail("Rode o Reconhecimento (clarify) ou edite os trechos marcados")
                }
                is EvalNode.PendingItem -> {
                    row.icon(AllIcons.General.BalloonWarning)
                    row.text("${node.marker.file.name}:${node.marker.line + 1}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    row.text(node.marker.text)
                    row.tooltip(node.marker.text)
                }
                is EvalNode.Plan -> {
                    val plan = node.report.plan ?: return
                    row.icon(AllIcons.FileTypes.Text)
                    row.text("plan.md")
                    plan.context.take(2).forEach { (_, v) -> row.pill(v.take(28), Tone.forKey(v)) }
                    if (plan.pending.isNotEmpty()) row.pill("${plan.pending.size} pendentes", Tone.YELLOW, bold = true)
                }
                is EvalNode.Context -> {
                    row.text(node.key, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    row.text(node.value)
                    row.tooltip(node.value)
                }
                is EvalNode.Design -> {
                    row.icon(AllIcons.Nodes.Folder)
                    row.text("Design")
                    row.pill("${node.docs.size} artefatos", Tone.TEAL)
                }
                is EvalNode.Doc -> {
                    row.icon(AllIcons.FileTypes.Text)
                    row.text(node.label)
                }
                is EvalNode.Tasks -> {
                    val tasks = node.report.tasks ?: return
                    row.icon(AllIcons.Nodes.EntryPoints)
                    row.text("tasks.md")
                    row.bar(listOf(Segment(tasks.done, Tone.GREEN.accent), Segment(tasks.total - tasks.done, Tone.ORANGE.accent)), 72)
                    row.pill("${tasks.done}/${tasks.total} feitas", if (tasks.total > 0 && tasks.done == tasks.total) Tone.GREEN else Tone.ORANGE)
                    val parallel = tasks.all.count { it.parallel }
                    if (parallel > 0) row.pill("$parallel paralelas", Tone.BLUE)
                }
                is EvalNode.Phase -> {
                    val phase = node.phase
                    row.icon(if (phase.done == phase.tasks.size) AllIcons.General.InspectionsOK else AllIcons.Nodes.Folder)
                    row.text(phase.title)
                    row.bar(listOf(Segment(phase.done, Tone.GREEN.accent), Segment(phase.tasks.size - phase.done, Tone.ORANGE.accent)))
                    row.text("${phase.done}/${phase.tasks.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is EvalNode.Task -> {
                    val task = node.task
                    row.icon(if (task.done) AllIcons.Actions.Checked else AllIcons.Nodes.EmptyNode)
                    task.id?.let { row.text(it, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES) }
                    if (task.parallel) row.pill("P", Tone.BLUE)
                    task.story?.let { row.pill(it, Tone.forKey(it)) }
                    val text = task.text.removePrefix(task.id.orEmpty()).replace("[P]", "").replace("[${task.story}]", "").trim()
                    row.text(text, if (task.done) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    row.tooltip(task.text)
                }
                is EvalNode.Checklists -> {
                    val open = node.lists.sumOf { it.items.size - it.done }
                    row.icon(AllIcons.Actions.Checked)
                    row.text("Checklists")
                    row.pill(if (open == 0) "Revisados" else "$open abertos", if (open == 0) Tone.GREEN else Tone.PINK)
                }
                is EvalNode.ChecklistFile -> {
                    val list = node.list
                    row.icon(AllIcons.FileTypes.Text)
                    row.text(list.file.name)
                    if (list.builtIn) row.pill("da spec", Tone.GRAY)
                    row.bar(listOf(Segment(list.done, Tone.GREEN.accent), Segment(list.items.size - list.done, Tone.PINK.accent)))
                    row.text("${list.done}/${list.items.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is EvalNode.Check -> {
                    row.icon(if (node.item.done) AllIcons.Actions.Checked else AllIcons.Nodes.EmptyNode)
                    row.text(node.item.text, if (node.item.done) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    row.tooltip(node.item.text)
                }
                is EvalNode.Missing -> {
                    row.icon(AllIcons.General.Add)
                    row.text(node.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    row.text("ainda não gerado: ${node.hint}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                null -> Unit
            }
        }
    }
}
